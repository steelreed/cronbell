package com.steelreed.cronbell.server.checks;

import java.time.Instant;

/**
 * The mutable part of a check.
 *
 * @param runStartedAt start of the run in progress, or null when no run is open
 * @param runRid the {@code rid} of the run in progress, if the job sent one
 */
public record CheckState(CheckStatus status, DownReason downReason, Instant lastPingAt, Instant lastStartAt,
		Instant lastSuccessAt, Instant nextDueAt, Instant runStartedAt, String runRid) {

	public static CheckState initial() {
		return new CheckState(CheckStatus.NEW, null, null, null, null, null, null, null);
	}

	public CheckState withStatus(CheckStatus status, DownReason reason) {
		return new CheckState(status, reason, this.lastPingAt, this.lastStartAt, this.lastSuccessAt, this.nextDueAt,
				this.runStartedAt, this.runRid);
	}

}
