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
		assertThat(HttpPoster.failure(new HttpPoster.Response(503, "", "7"), null, null).retryAfter())
			.isEqualTo(Duration.ofSeconds(7));
		assertThat(HttpPoster.failure(new HttpPoster.Response(429, "", null), "slow down", Duration.ofSeconds(3)))
			.satisfies((ex) -> {
				assertThat(ex.retryable()).isTrue();
				assertThat(ex.getMessage()).isEqualTo("HTTP 429: slow down");
			});
		assertThat(HttpPoster.failure(new HttpPoster.Response(408, "", null), null, null).retryable()).isTrue();
		assertThat(HttpPoster.failure(new HttpPoster.Response(404, "no_service", null), "no_service", null).retryable())
			.isFalse();
	}

	private static Channel channel(ChannelType type, Map<String, String> config) {
		Instant now = Instant.now();
		return new Channel("c1", "p1", type, type.id(), config, now, now);
	}

}
