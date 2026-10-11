package com.steelreed.cronbell.server.notify;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs webhook bodies: {@code sha256=<hex HMAC-SHA256 of "<timestamp>.<body>">} with the
 * channel secret (DESIGN.md §8). The timestamp is part of the signed text, so a receiver
 * that rejects old timestamps is safe from replays.
 */
public final class WebhookSigner {

	public static final String SIGNATURE_HEADER = "Cronbell-Signature";

	public static final String TIMESTAMP_HEADER = "Cronbell-Timestamp";

	public static final String DELIVERY_HEADER = "Cronbell-Delivery";

	private WebhookSigner() {
	}

	public static String sign(String secret, long timestampSeconds, byte[] body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			mac.update((timestampSeconds + ".").getBytes(StandardCharsets.UTF_8));
			return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
