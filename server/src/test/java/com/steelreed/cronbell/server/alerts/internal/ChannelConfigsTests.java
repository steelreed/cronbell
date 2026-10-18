package com.steelreed.cronbell.server.alerts.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ChannelConfigsTests {

	private static final String TOKEN = "123456789:AAbbCCddEEffGGhhIIjjKKllMMnnOOppQQr";

	@Test
	void telegramChatIds() {
		for (String chatId : new String[] { "42", "-1001234567890", "@cronbell_ops" }) {
			assertThat(ChannelConfigs.validate(ChannelType.TELEGRAM, Map.of("botToken", TOKEN, "chatId", chatId), null))
				.containsEntry("chatId", chatId);
		}
		for (String chatId : new String[] { "me", "@abc", "12a", "" }) {
			assertThatIllegalArgumentException().isThrownBy(() -> ChannelConfigs.validate(ChannelType.TELEGRAM,
					Map.of("botToken", TOKEN, "chatId", chatId), null));
		}
	}

	@Test
	void numericChatIdIsAccepted() {
		assertThat(ChannelConfigs.validate(ChannelType.TELEGRAM, Map.of("botToken", TOKEN, "chatId", -100123), null))
			.containsEntry("chatId", "-100123");
	}

	@Test
	void urlSchemes() {
		assertThat(ChannelConfigs.validate(ChannelType.WEBHOOK, Map.of("url", "http://n8n:5678/webhook/x"), null))
			.containsEntry("url", "http://n8n:5678/webhook/x");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ChannelConfigs.validate(ChannelType.SLACK, Map.of("webhookUrl", "http://x.test/"), null));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ChannelConfigs.validate(ChannelType.WEBHOOK, Map.of("url", "file:///etc/passwd"), null));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ChannelConfigs.validate(ChannelType.WEBHOOK, Map.of("url", "https:///nohost"), null));
	}

	@Test
	void emailAddresses() {
		assertThat(ChannelConfigs.validate(ChannelType.EMAIL, Map.of("address", " ops@example.com "), null))
			.containsEntry("address", "ops@example.com");
		for (String address : new String[] { "ops", "ops@", "a@b", "a b@example.com", "a@example.com,b@example.com" }) {
			assertThatIllegalArgumentException()
				.isThrownBy(() -> ChannelConfigs.validate(ChannelType.EMAIL, Map.of("address", address), null));
		}
	}

	@Test
	void masksSecrets() {
		Channel channel = channel(ChannelType.WEBHOOK,
				Map.of("url", "https://hooks.example.com:8443/T0/B0/secretpath?token=x", "secret", "whsec_0123456789"));
		assertThat(ChannelConfigs.masked(channel)).containsEntry("url", "https://hooks.example.com:8443/…")
			.containsEntry("secret", "wh…89");
		assertThat(ChannelConfigs.maskToken("short")).isEqualTo("****");
		assertThat(ChannelConfigs.masked(channel(ChannelType.TELEGRAM, Map.of("botToken", TOKEN, "chatId", "42"))))
			.containsEntry("botToken", "12…Qr")
			.containsEntry("chatId", "42");
	}

	@Test
	void updateKeepsSecretsThatAreOmittedOrMasked() {
		Map<String, String> stored = Map.of("botToken", TOKEN, "chatId", "42");
		assertThat(ChannelConfigs.validate(ChannelType.TELEGRAM, Map.of("chatId", "43"), stored))
			.containsEntry("botToken", TOKEN)
			.containsEntry("chatId", "43");
		assertThat(ChannelConfigs.validate(ChannelType.TELEGRAM, Map.of("botToken", "12…Qr", "chatId", "42"), stored))
			.containsEntry("botToken", TOKEN);
		Map<String, Object> nullToken = new HashMap<>();
		nullToken.put("botToken", null);
		nullToken.put("chatId", "42");
		assertThat(ChannelConfigs.validate(ChannelType.TELEGRAM, nullToken, stored)).containsEntry("botToken", TOKEN);
		// A plain field is not kept: it is part of the update.
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ChannelConfigs.validate(ChannelType.TELEGRAM, Map.of(), stored))
			.withMessageContaining("chatId");
	}

	@Test
	void scrubsSecretsFromErrors() {
		Channel channel = channel(ChannelType.TELEGRAM, Map.of("botToken", TOKEN, "chatId", "42"));
		assertThat(ChannelConfigs.scrub(channel, "POST https://api.telegram.org/bot" + TOKEN + "/sendMessage"))
			.isEqualTo("POST https://api.telegram.org/bot****/sendMessage");
	}

	@Test
	void backoffDoublesUpToFifteenMinutes() {
		assertThat(DeliveryDispatcher.backoff(1)).isEqualTo(Duration.ofSeconds(30));
		assertThat(DeliveryDispatcher.backoff(2)).isEqualTo(Duration.ofMinutes(1));
		assertThat(DeliveryDispatcher.backoff(5)).isEqualTo(Duration.ofMinutes(8));
		assertThat(DeliveryDispatcher.backoff(6)).isEqualTo(Duration.ofMinutes(15));
		assertThat(DeliveryDispatcher.backoff(40)).isEqualTo(Duration.ofMinutes(15));
	}

	@Test
	void scrubsTheBareWebhookPathAndLongerSecretsFirst() {
		Channel slack = channel(ChannelType.SLACK, Map.of("webhookUrl", "https://hooks.slack.com/services/T0/B0/xyz"));
		assertThat(
				ChannelConfigs.scrub(slack, "POST https://hooks.slack.com/services/T0/B0/xyz and /services/T0/B0/xyz"))
			.isEqualTo("POST **** and ****");
		// A path too short to hide anything is left alone.
		Channel webhook = channel(ChannelType.WEBHOOK, Map.of("url", "https://hooks.example.com/hook"));
		assertThat(ChannelConfigs.scrub(webhook, "POST /hook refused")).isEqualTo("POST /hook refused");
	}

	/**
	 * The receiver's body is cut to 200 characters; a secret it echoes across that limit
	 * must not leave its start in the message.
	 */
	@Test
	void failureScrubsSecretsBeforeShorteningTheBody() {
		String ntfyToken = "tk_" + "0123456789abcdef".repeat(2);
		String slackUrl = "https://hooks.slack.com/services/T0000/B0000/" + "s".repeat(24);
		String discordUrl = "https://discord.com/api/webhooks/123456/" + "d".repeat(60);
		String hookSecret = "whsec_" + "w".repeat(30);
		Map<Channel, String> echoed = Map.of(
				channel(ChannelType.NTFY, Map.of("serverUrl", "https://ntfy.sh", "topic", "ops", "token", ntfyToken)),
				ntfyToken, channel(ChannelType.TELEGRAM, Map.of("botToken", TOKEN, "chatId", "42")), TOKEN,
				channel(ChannelType.SLACK, Map.of("webhookUrl", slackUrl)), "/services/T0000/B0000/" + "s".repeat(24),
				channel(ChannelType.DISCORD, Map.of("webhookUrl", discordUrl)), discordUrl,
				channel(ChannelType.WEBHOOK, Map.of("url", "https://hooks.example.com/in", "secret", hookSecret)),
				hookSecret);
		echoed.forEach((channel, secret) -> {
			String body = "x".repeat(190) + secret + "\nend";
			String message = HttpPoster
				.failure(new HttpPoster.Response(500, body, null), channel, "receiver", body, null)
				.getMessage();
			assertThat(message).as(channel.type().id())
				.isEqualTo("HTTP 500 from receiver: " + "x".repeat(190) + "**** end");
			assertThat(message).as(channel.type().id()).doesNotContain(secret.substring(0, 5));
		});
	}

	@Test
	void retryableStatuses() {
		Channel slack = channel(ChannelType.SLACK, Map.of("webhookUrl", "https://hooks.slack.com/services/T0/B0/xyz"));
		assertThat(HttpPoster.failure(new HttpPoster.Response(503, "", "7"), slack, "Slack", null, null).retryAfter())
			.isEqualTo(Duration.ofSeconds(7));
		assertThat(HttpPoster.failure(new HttpPoster.Response(429, "", null), slack, "Discord", "slow down",
				Duration.ofSeconds(3)))
			.satisfies((ex) -> {
				assertThat(ex.retryable()).isTrue();
				assertThat(ex.getMessage()).isEqualTo("HTTP 429 from Discord: slow down");
				assertThat(ex.retryAfter()).isEqualTo(Duration.ofSeconds(3));
			});
		assertThat(HttpPoster.failure(new HttpPoster.Response(408, "", null), slack, "Slack", null, null).retryable())
			.isTrue();
		assertThat(HttpPoster.failure(new HttpPoster.Response(404, "no_service", null), slack, "Slack", "no_service",
				null))
			.satisfies((ex) -> {
				assertThat(ex.retryable()).isFalse();
				assertThat(ex.getMessage()).isEqualTo("HTTP 404 from Slack: no_service");
			});
		assertThat(HttpPoster.failure(new HttpPoster.Response(500, "", null), slack, "hooks.example.com", "  ", null)
			.getMessage()).isEqualTo("HTTP 500 from hooks.example.com");
	}

	@Test
	void discordWebhookUrls() {
		for (String url : new String[] { "https://discord.com/api/webhooks/1/abc",
				"https://canary.discord.com/api/webhooks/1/abc", "https://discordapp.com/api/webhooks/1/abc" }) {
			assertThat(ChannelConfigs.validate(ChannelType.DISCORD, Map.of("webhookUrl", url), null))
				.containsEntry("webhookUrl", url);
		}
		for (String url : new String[] { "http://discord.com/api/webhooks/1/abc", "https://discord.com/channels/1/2",
				"https://evil-discord.com/api/webhooks/1/abc", "https://discord.com.example.com/api/webhooks/1/abc" }) {
			assertThatIllegalArgumentException()
				.isThrownBy(() -> ChannelConfigs.validate(ChannelType.DISCORD, Map.of("webhookUrl", url), null))
				.withMessageContaining("webhookUrl");
		}
	}

	@Test
	void ntfyDefaultsToNtfyShAndTakesSelfHostedServers() {
		assertThat(ChannelConfigs.validate(ChannelType.NTFY, Map.of("topic", "cronbell-ops"), null))
			.containsEntry("serverUrl", "https://ntfy.sh")
			.containsEntry("topic", "cronbell-ops")
			.doesNotContainKey("token");
		assertThat(
				ChannelConfigs.validate(ChannelType.NTFY,
						Map.of("serverUrl", "http://ntfy.internal:8080/", "topic", "ops", "token",
								"tk_abcdefghijklmnop"),
						null))
			.containsEntry("serverUrl", "http://ntfy.internal:8080")
			.containsEntry("token", "tk_abcdefghijklmnop");
	}

	@Test
	void ntfyRejectsBadTopicsAndPasswords() {
		for (String topic : new String[] { "", "ops/alerts", "has space", "x".repeat(65) }) {
			assertThatIllegalArgumentException()
				.isThrownBy(() -> ChannelConfigs.validate(ChannelType.NTFY, Map.of("topic", topic), null))
				.withMessageContaining("topic");
		}
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ChannelConfigs.validate(ChannelType.NTFY,
					Map.of("serverUrl", "https://phil:secret@ntfy.example.com", "topic", "ops"), null))
			.withMessageContaining("config.token");
	}

	@Test
	void masksNtfyToken() {
		Channel channel = channel(ChannelType.NTFY,
				Map.of("serverUrl", "https://ntfy.sh", "topic", "ops", "token", "tk_abcdefghijklmnop"));
		assertThat(ChannelConfigs.masked(channel)).containsEntry("serverUrl", "https://ntfy.sh")
			.containsEntry("topic", "ops")
			.containsEntry("token", "tk…op");
	}

	private static Channel channel(ChannelType type, Map<String, String> config) {
		Instant now = Instant.now();
		return new Channel("c1", "p1", type, type.id(), config, now, now);
	}

}
