package com.steelreed.cron.server.db;

import java.security.SecureRandom;
import java.util.UUID;

/** Row identifiers: time-ordered UUIDv7 strings, generated in the application. */
public final class Ids {

	private static final SecureRandom RANDOM = new SecureRandom();

	private Ids() {
	}

	public static String newId() {
		return uuidV7(System.currentTimeMillis()).toString();
	}

	static UUID uuidV7(long epochMillis) {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		for (int i = 0; i < 6; i++) {
			bytes[i] = (byte) (epochMillis >>> (40 - 8 * i));
		}
		bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x70);
		bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
		long msb = 0;
		long lsb = 0;
		for (int i = 0; i < 8; i++) {
			msb = (msb << 8) | (bytes[i] & 0xff);
			lsb = (lsb << 8) | (bytes[i + 8] & 0xff);
		}
		return new UUID(msb, lsb);
	}

}
