package com.steelreed.cronbell.server.notify;

import java.time.Instant;

import com.steelreed.cronbell.server.checks.CheckStatus;
import com.steelreed.cronbell.server.checks.DownReason;

/**
 * What an alert says, captured when the check changed status so retries send the same
 * message. Stored as JSON in the outbox.
 *
 * @param at when the status changed
 * @param expectedAt the missed due time (MISSED) or the next due time (UP)
 * @param exitCode exit code of the failed run (FAILED), if the job sent one
 * @param runStartedAt start of the run that took too long (RUNNING_TOO_LONG)
 * @param log the latest ping body (DOWN only), already cut to its last characters
 * @param url link to the check, when a base URL is configured
 */
public record Alert(Kind kind, Instant at, String slug, String name, CheckStatus status, DownReason downReason,
		String timeZone, Instant expectedAt, Long graceSeconds, Integer exitCode, Instant runStartedAt,
		Long maxRuntimeSeconds, Instant lastSuccessAt, String log, Instant logAt, String url) {

	public enum Kind {

		DOWN, UP, TEST

	}

	/** The message sent by {@code POST /api/v1/channels/{id}/test}. */
	public static Alert test(Instant at) {
		return new Alert(Kind.TEST, at, null, null, null, null, "UTC", null, null, null, null, null, null, null, null,
				null);
	}

}
