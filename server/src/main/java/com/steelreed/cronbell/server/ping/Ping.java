package com.steelreed.cronbell.server.ping;

import java.time.Instant;

import com.steelreed.cronbell.server.checks.PingKind;

/**
 * One stored ping. {@code body} is at most {@link PingService#MAX_BODY_BYTES} bytes of
 * UTF-8.
 */
public record Ping(String id, String checkId, PingKind kind, Integer exitCode, String rid, String body,
		Instant receivedAt, String remoteAddr, String userAgent) {
}
