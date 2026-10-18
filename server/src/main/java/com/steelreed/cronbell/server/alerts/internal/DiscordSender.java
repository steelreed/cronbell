package com.steelreed.cronbell.server.alerts.internal;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/** Discord webhook: one embed per alert. */
@Component
final class DiscordSender implements ChannelSender {

	private final HttpPoster http;

	private final JsonMapper json;

	DiscordSender(HttpPoster http, JsonMapper json) {
		this.http = http;
		this.json = json;
	}

	@Override
	public ChannelType type() {
		return ChannelType.DISCORD;
	}

	@Override
	public void send(Channel channel, Alert alert, String deliveryId) throws DeliveryException {
		byte[] body = this.json.writeValueAsBytes(MessageRenderer.discordPayload(alert));
		HttpPoster.Response response = this.http.post(URI.create(channel.get(ChannelConfigs.WEBHOOK_URL)), Map.of(),
				body);
		if (!response.successful()) {
			// {"message": "You are being rate limited.", "retry_after": 1.5, "global":
			// false}, or {"message": "Unknown Webhook", "code": 10015}
			String detail = response.body();
			Duration retryAfter = null;
			try {
				JsonNode error = this.json.readTree(response.body());
				if (error.hasNonNull("message")) {
					detail = error.get("message").asString();
				}
				JsonNode wait = error.path("retry_after");
				if (wait.isNumber()) {
					retryAfter = Duration.ofMillis((long) Math.ceil(wait.asDouble() * 1000));
				}
			}
			catch (JacksonException ex) {
				// Not JSON (a proxy error page): keep the raw body.
			}
			if (response.status() == 401 || response.status() == 404) {
				detail = SlackSender.WEBHOOK_GONE;
			}
			throw HttpPoster.failure(response, "Discord", detail, retryAfter);
		}
	}

}
