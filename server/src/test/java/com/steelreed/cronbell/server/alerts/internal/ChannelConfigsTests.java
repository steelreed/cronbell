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
	void retryableStatuses() {
		assertThat(HttpPoster.failure(new HttpPoster.Response(503, "", "7"), "Slack", null, null).retryAfter())
			.isEqualTo(Duration.ofSeconds(7));
		assertThat(HttpPoster.failure(new HttpPoster.Response(429, "", null), "Discord", "slow down",
				Duration.ofSeconds(3)))
			.satisfies((ex) -> {
				assertThat(ex.retryable()).isTrue();
				assertThat(ex.getMessage()).isEqualTo("HTTP 429 from Discord: slow down");
				assertThat(ex.retryAfter()).isEqualTo(Duration.ofSeconds(3));
			});
		assertThat(HttpPoster.failure(new HttpPoster.Response(408, "", null), "Slack", null, null).retryable())
			.isTrue();
		assertThat(HttpPoster.failure(new HttpPoster.Response(404, "no_service", null), "Slack", "no_service", null))
			.satisfies((ex) -> {
				assertThat(ex.retryable()).isFalse();
				assertThat(ex.getMessage()).isEqualTo("HTTP 404 from Slack: no_service");
			});
		assertThat(HttpPoster.failure(new HttpPoster.Response(500, "", null), "hooks.example.com", "  ", null)
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
