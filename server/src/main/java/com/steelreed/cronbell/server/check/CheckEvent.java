package com.steelreed.cronbell.server.check;

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

	static CheckEvent of(Check check, CheckStateMachine.Change change, CheckState next, Instant at) {
		return switch (change) {
			case WENT_DOWN -> new WentDown(check.id(), check.projectId(), check.slug(), next.downReason(), at);
			case RECOVERED -> new Recovered(check.id(), check.projectId(), check.slug(), at);
			case NONE -> throw new IllegalArgumentException("no event for NONE");
		};
	}

}
