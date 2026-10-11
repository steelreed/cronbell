package com.steelreed.cronbell.server.notify;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignerTests {

	private static final byte[] BODY = "{\"version\":1}".getBytes(StandardCharsets.UTF_8);

	@Test
	void signsTimestampDotBodyWithHmacSha256() {
		// python3: hmac.new(b"whsec_example", b'1767607200.{"version":1}',
		// hashlib.sha256).hexdigest()
		assertThat(WebhookSigner.sign("whsec_example", 1767607200L, BODY))
			.isEqualTo("sha256=31f000fd3c1ef08cf5f1deb9cb83b76d406c57bf7b950979b82a1ba8163e312f");
	}

	@Test
	void timestampIsPartOfTheSignature() {
		assertThat(WebhookSigner.sign("whsec_example", 1767607201L, BODY))
			.isNotEqualTo(WebhookSigner.sign("whsec_example", 1767607200L, BODY));
	}

}
