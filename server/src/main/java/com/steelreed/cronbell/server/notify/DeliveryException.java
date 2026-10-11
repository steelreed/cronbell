package com.steelreed.cronbell.server.notify;

import java.time.Duration;

/**
 * A failed send. The message is short and safe to store and show: it never contains a URL
 * or secret of the channel.
 */
public class DeliveryException extends Exception {

	private final boolean retryable;

	private final Duration retryAfter;

	DeliveryException(String message, boolean retryable, Duration retryAfter) {
		super(message, null, false, false);
		this.retryable = retryable;
		this.retryAfter = retryAfter;
	}

	static DeliveryException retryable(String message) {
		return new DeliveryException(message, true, null);
	}

	static DeliveryException permanent(String message) {
		return new DeliveryException(message, false, null);
	}

	/** Network errors, timeouts, 408, 429 and 5xx are retried; other errors are not. */
	public boolean retryable() {
		return this.retryable;
	}

	/** How long the receiver asked us to wait ({@code Retry-After}), if it did. */
	public Duration retryAfter() {
		return this.retryAfter;
	}

}
