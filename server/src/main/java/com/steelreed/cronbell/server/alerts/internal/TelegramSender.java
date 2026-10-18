package com.steelreed.cronbell.server.alerts.internal;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/** Telegram Bot API {@code sendMessage} with {@code parse_mode=HTML}. */
@Component
final class TelegramSender implements ChannelSender {

	private final HttpPoster http;

	private final JsonMapper json;

	private final String apiUrl;

	TelegramSender(HttpPoster http, JsonMapper json, NotifierProperties properties) {
		this.http = http;
		this.json = json;
		this.apiUrl = properties.telegramApiUrl().replaceAll("/+$", "");
	}

	@Override
	public ChannelType type() {
		return ChannelType.TELEGRAM;
	}

	@Override
	public void send(Channel channel, Alert alert, String deliveryId) throws DeliveryException {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("chat_id", channel.get(ChannelConfigs.CHAT_ID));
		message.put("text", MessageRenderer.telegramHtml(alert));
		message.put("parse_mode", "HTML");
		message.put("link_preview_options", Map.of("is_disabled", true));
		URI uri = URI.create(this.apiUrl + "/bot" + channel.get(ChannelConfigs.BOT_TOKEN) + "/sendMessage");
		HttpPoster.Response response = this.http.post(uri, Map.of(), this.json.writeValueAsBytes(message));
		if (!response.successful()) {
			// {"ok": false, "error_code": 400, "description": "Bad Request: chat not
			// found", "parameters": {"retry_after": 5}}
			String detail = response.body();
			Duration retryAfter = null;
			try {
				JsonNode error = this.json.readTree(response.body());
				if (error.hasNonNull("description")) {
					detail = error.get("description").asString();
				}
				JsonNode wait = error.path("parameters").path("retry_after");
				if (wait.isNumber()) {
					retryAfter = Duration.ofSeconds(wait.asLong());
				}
			}
			catch (JacksonException ex) {
				// Not JSON (a proxy error page): keep the raw body.
			}
			if (response.status() == 401 || response.status() == 404) {
				detail = "check the bot token";
			}
			throw HttpPoster.failure(response, channel, "Telegram", detail, retryAfter);
		}
	}

}
