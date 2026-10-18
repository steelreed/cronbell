package com.steelreed.cronbell.server.alerts.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
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

	static final String SERVER_URL = "serverUrl";

	static final String TOPIC = "topic";

	static final String TOKEN = "token";

	static final String NTFY_SERVER = "https://ntfy.sh";

	private static final int MAX_URL_LENGTH = 2000;

	/**
	 * Shorter paths of a secret URL ({@code /}, {@code /hook}) are not scrubbed on their
	 * own: they hide nothing and would blank out ordinary words of a message.
	 */
	private static final int MIN_SECRET_PATH = 8;

	private static final Pattern EMAIL = Pattern
		.compile("[^\\s@<>,;:\"()\\[\\]]+@[^\\s@<>,;:\"()\\[\\]]+\\.[^\\s@<>,;:\"()\\[\\]]+");

	private static final Pattern BOT_TOKEN_FORMAT = Pattern.compile("\\d{1,20}:[A-Za-z0-9_-]{30,100}");

	private static final Pattern CHAT_ID_FORMAT = Pattern.compile("-?\\d{1,20}|@[A-Za-z][A-Za-z0-9_]{4,31}");

	/** What ntfy accepts as a topic name. */
	private static final Pattern TOPIC_FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

	private enum Kind {

		PLAIN, TOKEN, URL

	}

	/**
	 * A config field; {@code defaultValue} is stored when an optional field is left
	 * empty.
	 */
	private record Field(String name, Kind kind, boolean required, String defaultValue) {

		Field(String name, Kind kind, boolean required) {
			this(name, kind, required, null);
		}

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
			case SLACK, DISCORD -> List.of(new Field(WEBHOOK_URL, Kind.URL, true));
			case NTFY -> List.of(new Field(SERVER_URL, Kind.PLAIN, false, NTFY_SERVER),
					new Field(TOPIC, Kind.PLAIN, true), new Field(TOKEN, Kind.TOKEN, false));
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
				if (field.defaultValue() != null) {
					result.put(field.name(), field.defaultValue());
				}
				else if (field.required()) {
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
			case WEBHOOK_URL -> {
				URI uri = checkUrl(name, value, false);
				if (type == ChannelType.DISCORD && !isDiscordWebhook(uri)) {
					throw new IllegalArgumentException(
							"config.webhookUrl must be a Discord webhook URL (https://discord.com/api/webhooks/…)");
				}
			}
			case URL -> checkUrl(name, value, type == ChannelType.WEBHOOK);
			case SERVER_URL -> {
				if (checkUrl(name, value, true).getRawUserInfo() != null) {
					throw new IllegalArgumentException(
							"config.serverUrl must not hold a user name or password; use config.token");
				}
				return value.replaceAll("/+$", "");
			}
			case TOPIC -> {
				if (!TOPIC_FORMAT.matcher(value).matches()) {
					throw new IllegalArgumentException(
							"config.topic must be 1 to 64 letters, digits, '_' or '-' (ntfy topic names)");
				}
			}
			case SECRET, TOKEN -> {
				if (value.length() > 200) {
					throw new IllegalArgumentException("config." + name + " must be at most 200 characters");
				}
			}
			default -> throw new IllegalStateException("unexpected field " + name);
		}
		return value;
	}

	/**
	 * Slack and Discord need https. Generic webhooks and ntfy servers may use http too:
	 * self-hosted installs post to services on their own network (DESIGN.md §8).
	 */
	private static URI checkUrl(String name, String value, boolean allowHttp) {
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
		return uri;
	}

	/** discord.com, its ptb. and canary. hosts, or the older discordapp.com. */
	private static boolean isDiscordWebhook(URI uri) {
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		boolean discord = host.equals("discord.com") || host.endsWith(".discord.com") || host.equals("discordapp.com")
				|| host.endsWith(".discordapp.com");
		return discord && uri.getRawPath() != null && uri.getRawPath().startsWith("/api/webhooks/");
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

	/**
	 * Secret values of the channel, to scrub from error messages, longest first. A secret
	 * URL also gives its path and query, which a receiver may echo without the host.
	 */
	static List<String> secrets(Channel channel) {
		List<String> result = new ArrayList<>();
		for (Field field : fields(channel.type())) {
			String value = channel.get(field.name());
			if (!field.secret() || value == null || value.isEmpty()) {
				continue;
			}
			result.add(value);
			String path = (field.kind() == Kind.URL) ? pathOf(value) : null;
			if (path != null && path.length() >= MIN_SECRET_PATH) {
				result.add(path);
			}
		}
		result.sort(Comparator.comparingInt(String::length).reversed());
		return result;
	}

	private static String pathOf(String url) {
		try {
			URI uri = new URI(url);
			String path = (uri.getRawPath() != null) ? uri.getRawPath() : "";
			return (uri.getRawQuery() != null) ? path + "?" + uri.getRawQuery() : path;
		}
		catch (URISyntaxException ex) {
			return null;
		}
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

	/**
	 * Replaces every secret of the channel in {@code text} with {@code ****}. Scrub the
	 * whole text before shortening it: a secret cut at the end no longer matches.
	 */
	static String scrub(Channel channel, String text) {
		if (text == null) {
			return null;
		}
		String result = text;
		for (String secret : secrets(channel)) {
			result = result.replace(secret, "****");
		}
		return result;
	}

}
