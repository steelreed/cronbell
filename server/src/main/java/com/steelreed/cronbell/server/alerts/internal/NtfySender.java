package com.steelreed.cronbell.server.alerts.internal;

import java.net.URI;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * ntfy: a JSON message published to the channel's server (ntfy.sh or a self-hosted one),
 * with its access token when it has one.
 */
@Component
final class NtfySender implements ChannelSender {

	private final HttpPoster http;

	private final JsonMapper json;

	NtfySender(HttpPoster http, JsonMapper json) {
		this.http = http;
		this.json = json;
	}

	@Override
	public ChannelType type() {
		return ChannelType.NTFY;
	}

	@Override
	public void send(Channel channel, Alert alert, String deliveryId) throws DeliveryException {
		byte[] body = this.json
			.writeValueAsBytes(MessageRenderer.ntfyPayload(alert, channel.get(ChannelConfigs.TOPIC)));
		String token = channel.get(ChannelConfigs.TOKEN);
		Map<String, String> headers = (token != null) ? Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + token) : Map.of();
		HttpPoster.Response response = this.http.post(URI.create(channel.get(ChannelConfigs.SERVER_URL)), headers,
				body);
		if (!response.successful()) {
			// {"code": 40301, "http": 403, "error": "forbidden", "link": "…"}
			String detail = response.body();
			try {
				JsonNode error = this.json.readTree(response.body());
				if (error.hasNonNull("error")) {
					detail = error.get("error").asString();
				}
			}
			catch (JacksonException ex) {
				// Not JSON (a proxy error page): keep the raw body.
			}
			detail = switch (response.status()) {
				case 401 -> "check the access token";
				case 403 -> (token != null) ? "the access token may not publish to this topic"
						: "the topic needs an access token";
				default -> detail;
			};
			throw HttpPoster.failure(response, channel, "ntfy", detail, null);
		}
	}

}
