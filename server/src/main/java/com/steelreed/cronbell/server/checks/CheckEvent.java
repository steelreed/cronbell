package com.steelreed.cronbell.server.checks;

import java.time.Instant;

/**
 * Published on UP → DOWN and DOWN → UP (§3). Listeners that act on it (the notifier)
 * should use {@code @TransactionalEventListener} so they only see committed changes.
 */
public sealed interface CheckEvent {

	String checkId();

	String projectId();

	String slug();

	Instant at();

	/** The check went DOWN for {@code reason}. */
	record WentDown(String checkId, String projectId, String slug, DownReason reason,
			Instant at) implements CheckEvent {
	}

	/** A successful run brought the check back UP. */
	record Recovered(String checkId, String projectId, String slug, Instant at) implements CheckEvent {
	}

}
