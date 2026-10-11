package com.steelreed.cronbell.server.notify;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates, merges and masks the config of each channel type (DESIGN.md §8). Secret
 * fields are never returned in full.
 */
public final class ChannelConfigs {

	static final String ADDRESS = "address";

	static final String BOT_TOKEN = "botToken";

	static final String CHAT_ID = "chatId";

	static final String WEBHOOK_URL = "webhookUrl";

	static final String URL = "url";

	static final String SECRET = "secret";

	private static final int MAX_URL_LENGTH = 2000;

	private static final Pattern EMAIL = Pattern
		.compile("[^\\s@<>,;:\"()\\[\\]]+@[^\\s@<>,;:\"()\\[\\]]+\\.[^\\s@<>,;:\"()\\[\\]]+");

	private static final Pattern BOT_TOKEN_FORMAT = Pattern.compile("\\d{1,20}:[A-Za-z0-9_-]{30,100}");

	private static final Pattern CHAT_ID_FORMAT = Pattern.compile("-?\\d{1,20}|@[A-Za-z][A-Za-z0-9_]{4,31}");

	private enum Kind {

		PLAIN, TOKEN, URL

	}

	private record Field(String name, Kind kind, boolean required) {

		boolean secret() {
			return this.kind != Kind.PLAIN;
		}

	}

	private ChannelConfigs() {
	}

	private static List<Field> fields(ChannelType type) {
		return switch (type) {
			case EMAIL -> List.of(new Field(ADDRESS, Kind.PLAIN, true));
			case TELEGRAM -> List.of(new Field(BOT_TOKEN, Kind.TOKEN, true), new Field(CHAT_ID, Kind.PLAIN, true));
			case SLACK -> List.of(new Field(WEBHOOK_URL, Kind.URL, true));
			case WEBHOOK -> List.of(new Field(URL, Kind.URL, true), new Field(SECRET, Kind.TOKEN, false));
		};
	}

	/**
	 * Validates a config sent by a client. For an update, {@code current} is the stored
	 * config: a secret field that is absent, {@code null} or equal to its masked value
	 * keeps the stored value.
	 * @return the config to store
	 * @throws IllegalArgumentException if the config is invalid
	 */
	public static Map<String, String> validate(ChannelType type, Map<String, ?> input, Map<String, String> current) {
		Map<String, ?> given = (input != null) ? input : Map.of();
		List<Field> fields = fields(type);
		for (String key : given.keySet()) {
			if (fields.stream().noneMatch((field) -> field.name().equals(key))) {
				throw new IllegalArgumentException("unknown config field for " + type.id() + ": " + key);
			}
		}
		Map<String, String> result = new LinkedHashMap<>();
		for (Field field : fields) {
			Object raw = given.get(field.name());
			String value = (raw != null) ? raw.toString().trim() : null;
			String stored = (current != null) ? current.get(field.name()) : null;
			if (field.secret() && stored != null && (value == null || value.equals(mask(field.kind(), stored)))) {
				value = stored;
			}
			if (value == null || value.isEmpty()) {
				if (field.required()) {
					throw new IllegalArgumentException("config." + field.name() + " is required for " + type.id());
				}
				continue;
			}
			result.put(field.name(), check(type, field.name(), value));
		}
		return result;
	}

	private static String check(ChannelType type, String name, String value) {
		switch (name) {
			case ADDRESS -> {
				if (value.length() > 254 || !EMAIL.matcher(value).matches()) {
					throw new IllegalArgumentException("config.address must be one email address");
				}
			}
			case BOT_TOKEN -> {
				if (!BOT_TOKEN_FORMAT.matcher(value).matches()) {
					throw new IllegalArgumentException(
							"config.botToken must be a Telegram bot token (<digits>:<35 characters>)");
				}
			}
			case CHAT_ID -> {
				if (!CHAT_ID_FORMAT.matcher(value).matches()) {
					throw new IllegalArgumentException(
							"config.chatId must be a numeric chat id (groups are negative) or @channelname");
				}
			}
			case WEBHOOK_URL -> checkUrl(name, value, false);
			case URL -> checkUrl(name, value, type == ChannelType.WEBHOOK);
			case SECRET -> {
				if (value.length() > 200) {
					throw new IllegalArgumentException("config.secret must be at most 200 characters");
				}
			}
			default -> throw new IllegalStateException("unexpected field " + name);
		}
		return value;
	}

	/**
	 * Slack needs https. Generic webhooks may use http too: self-hosted installs post to
	 * services on their own network (DESIGN.md §8).
	 */
	private static void checkUrl(String name, String value, boolean allowHttp) {
		String schemes = allowHttp ? "an http:// or https://" : "an https://";
		URI uri;
		try {
			uri = new URI(value);
		}
		catch (URISyntaxException ex) {
			throw new IllegalArgumentException("config." + name + " must be " + schemes + " URL");
		}
		String scheme = (uri.getScheme() != null) ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
		boolean schemeOk = scheme.equals("https") || (allowHttp && scheme.equals("http"));
		if (!schemeOk || uri.getHost() == null || uri.getHost().isEmpty() || value.length() > MAX_URL_LENGTH) {
			throw new IllegalArgumentException("config." + name + " must be " + schemes + " URL");
		}
	}

	/** The config as the API returns it: secret fields masked. */
	public static Map<String, String> masked(Channel channel) {
		Map<String, String> result = new LinkedHashMap<>();
		for (Field field : fields(channel.type())) {
			String value = channel.get(field.name());
			if (value != null) {
				result.put(field.name(), mask(field.kind(), value));
			}
		}
		return result;
	}

	/** Secret values of the channel, to scrub from error messages. */
	static List<String> secrets(Channel channel) {
		List<String> result = new ArrayList<>();
		for (Field field : fields(channel.type())) {
			String value = channel.get(field.name());
			if (field.secret() && value != null) {
				result.add(value);
			}
		}
		return result;
	}

	private static String mask(Kind kind, String value) {
		return switch (kind) {
			case PLAIN -> value;
			case TOKEN -> maskToken(value);
			case URL -> maskUrl(value);
		};
	}

	static String maskToken(String value) {
		if (value.length() < 12) {
			return "****";
		}
		return value.substring(0, 2) + "…" + value.substring(value.length() - 2);
	}

	static String maskUrl(String value) {
		try {
			URI uri = new URI(value);
			if (uri.getScheme() == null || uri.getHost() == null) {
				return "****";
			}
			String port = (uri.getPort() != -1) ? ":" + uri.getPort() : "";
			return uri.getScheme() + "://" + uri.getHost() + port + "/…";
		}
		catch (URISyntaxException ex) {
			return "****";
		}
	}

	/** Replaces every secret of the channel in {@code text} with {@code ****}. */
	static String scrub(Channel channel, String text) {
		if (text == null) {
			return null;
		}
		String result = text;
		for (String secret : secrets(channel)) {
			if (!secret.isEmpty()) {
				result = result.replace(secret, "****");
			}
		}
		return result;
	}

}
