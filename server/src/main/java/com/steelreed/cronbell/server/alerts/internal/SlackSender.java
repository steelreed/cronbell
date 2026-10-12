package com.steelreed.cronbell.server.alerts.internal;

import java.net.URI;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/** Slack incoming webhook: {@code {"text": …}} in mrkdwn. */
@Component
class SlackSender implements ChannelSender {

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
			throw HttpPoster.failure(response, response.body(), null);
		}
	}

}
