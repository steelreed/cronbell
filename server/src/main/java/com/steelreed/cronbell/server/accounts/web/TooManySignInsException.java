package com.steelreed.cronbell.server.accounts.web;

import java.time.Duration;

/** Sign-in is blocked for this client and email after repeated failures. */
class TooManySignInsException extends RuntimeException {

	private final Duration retryAfter;

	TooManySignInsException(Duration retryAfter) {
		super("too many failed sign-ins, try again in " + seconds(retryAfter) + " s");
		this.retryAfter = retryAfter;
	}

	long retryAfterSeconds() {
		return seconds(this.retryAfter);
	}

	private static long seconds(Duration duration) {
		return Math.max(1, (duration.toMillis() + 999) / 1000);
	}

}
