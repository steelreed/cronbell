package com.steelreed.cronbell.server.notify;

import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/**
 * Generic webhook: the version 1 JSON body, a delivery id, a timestamp, and an HMAC
 * signature when the channel has a secret.
 */
@Component
class WebhookSender implements ChannelSender {

	private final HttpPoster http;

	private final JsonMapper json;

	private final Clock clock;

	WebhookSender(HttpPoster http, JsonMapper json, Clock clock) {
		this.http = http;
		this.json = json;
		this.clock = clock;
	}

	@Override
	public ChannelType type() {
		return ChannelType.WEBHOOK;
	}

	@Override
	public void send(Channel channel, Alert alert, String deliveryId) throws DeliveryException {
		byte[] body = this.json.writeValueAsBytes(MessageRenderer.webhookPayload(alert, deliveryId));
		long timestamp = this.clock.instant().getEpochSecond();
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put(WebhookSigner.DELIVERY_HEADER, deliveryId);
		headers.put(WebhookSigner.TIMESTAMP_HEADER, Long.toString(timestamp));
		String secret = channel.get(ChannelConfigs.SECRET);
		if (secret != null) {
			headers.put(WebhookSigner.SIGNATURE_HEADER, WebhookSigner.sign(secret, timestamp, body));
		}
		HttpPoster.Response response = this.http.post(URI.create(channel.get(ChannelConfigs.URL)), headers, body);
		if (!response.successful()) {
			throw HttpPoster.failure(response, response.body(), null);
		}
	}

}
