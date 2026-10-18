package com.steelreed.cronbell.server.alerts.internal;

import java.net.URI;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/** Slack incoming webhook: {@code {"text": …}} in mrkdwn. */
@Component
final class SlackSender implements ChannelSender {

	/** What a 401, 403 or 404 from a chat webhook means. */
	static final String WEBHOOK_GONE = "the webhook was deleted or its URL is wrong";

	private final HttpPoster http;

	private final JsonMapper json;

	SlackSender(HttpPoster http, JsonMapper json) {
		this.http = http;
		this.json = json;
	}

	@Override
	public ChannelType type() {
		return ChannelType.SLACK;
	}

	@Override
	public void send(Channel channel, Alert alert, String deliveryId) throws DeliveryException {
		byte[] body = this.json.writeValueAsBytes(Map.of("text", MessageRenderer.slackText(alert)));
		HttpPoster.Response response = this.http.post(URI.create(channel.get(ChannelConfigs.WEBHOOK_URL)), Map.of(),
				body);
		if (!response.successful()) {
			// Slack answers a short code such as no_service or channel_is_archived.
			String detail = switch (response.status()) {
				case 401, 403, 404 -> WEBHOOK_GONE;
				default -> response.body();
			};
			throw HttpPoster.failure(response, "Slack", detail, null);
		}
	}

}
