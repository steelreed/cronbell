package com.steelreed.cronbell.server.alerts.internal;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.Locale;

import javax.net.ssl.SSLException;

/**
 * Plain sentences for sends that got no answer, kept as a channel's last error (DESIGN.md
 * §8). They name the receiver by host and port, never by URL: a webhook URL can be a
 * secret.
 */
final class DeliveryErrors {

	private DeliveryErrors() {
	}

	/**
	 * Why the connection to {@code host:port} failed: the name did not resolve, the host
	 * refused or did not answer in time, TLS failed, or another I/O error.
	 */
	static String network(Throwable error, String host, int port, Duration connectTimeout, Duration readTimeout) {
		String address = host + ":" + port;
		if (find(error, UnknownHostException.class) != null || find(error, UnresolvedAddressException.class) != null) {
			return "Could not resolve " + host;
		}
		Throwable timeout = find(error, HttpTimeoutException.class);
		if (timeout == null) {
			timeout = find(error, SocketTimeoutException.class);
		}
		if (timeout != null) {
			boolean connecting = timeout instanceof HttpConnectTimeoutException || (timeout.getMessage() != null
					&& timeout.getMessage().toLowerCase(Locale.ROOT).contains("connect"));
			return connecting ? "Timed out connecting to " + address + " after " + seconds(connectTimeout)
					: "Timed out after " + seconds(readTimeout) + " waiting for " + host;
		}
		if (find(error, SSLException.class) != null) {
			return "TLS handshake with " + address + " failed";
		}
		String reason = rootMessage(error);
		if (find(error, ConnectException.class) != null) {
			boolean refused = reason == null || reason.toLowerCase(Locale.ROOT).contains("refused");
			return refused ? "Connection refused by " + address : "Could not connect to " + address + ": " + reason;
		}
		return withReason("Request to " + address + " failed", error);
	}

	/** The sentence, followed by the innermost message of the error if it has one. */
	static String withReason(String sentence, Throwable error) {
		String reason = rootMessage(error);
		return sentence + ((reason != null) ? ": " + reason : "");
	}

	/** The innermost cause of this type in the chain, or null. */
	static Throwable find(Throwable error, Class<? extends Throwable> type) {
		Throwable found = null;
		for (Throwable cause = error; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
			if (type.isInstance(cause)) {
				found = cause;
			}
		}
		return found;
	}

	/** The message of the innermost cause that has one, on one short line. */
	private static String rootMessage(Throwable error) {
		String message = null;
		for (Throwable cause = error; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
			if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
				message = cause.getMessage();
			}
		}
		return (message != null) ? HttpPoster.oneLine(message) : null;
	}

	private static String seconds(Duration duration) {
		return (duration.toMillis() % 1000 == 0) ? duration.toSeconds() + " s" : duration.toMillis() + " ms";
	}

}
