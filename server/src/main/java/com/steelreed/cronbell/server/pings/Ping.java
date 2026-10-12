package com.steelreed.cronbell.server.pings;

import java.time.Instant;

import com.steelreed.cronbell.server.checks.PingKind;

/**
 * One stored ping. {@code body} is at most {@link #MAX_BODY_BYTES} bytes of UTF-8.
 */
public record Ping(String id, String checkId, PingKind kind, Integer exitCode, String rid, String body,
		Instant receivedAt, String remoteAddr, String userAgent) {

	/** Bodies are stored up to this many bytes and truncated beyond. */
	public static final int MAX_BODY_BYTES = 10 * 1024;

}
