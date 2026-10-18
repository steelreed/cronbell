package com.steelreed.cronbell.server.checks;

import java.time.Instant;

/**
 * A status change of a check, kept as its history (DESIGN.md §4, Status history).
 *
 * @param reason why the check went DOWN; null for other kinds
 * @param runAt the run the change is about: the missed due time (MISSED) or the start of
 * the run that took too long (RUNNING_TOO_LONG); else null
 */
public record StatusEvent(String id, String checkId, String projectId, Kind kind, DownReason reason, Instant runAt,
		Instant at) {

	public enum Kind {

		/** A run succeeded: the first one, the first after a pause, or a recovery. */
		UP,

		DOWN,

		PAUSED,

		/** A pause ended without a ping (§3): UP again, or NEW if never pinged. */
		RESUMED;

		/**
		 * The event of entering {@code status} through a ping, the detector or a pause.
		 */
		public static Kind entering(CheckStatus status) {
			return switch (status) {
				case UP -> UP;
				case DOWN -> DOWN;
				case PAUSED -> PAUSED;
				case NEW -> RESUMED;
			};
		}

	}

	/** The event of a check entering {@code state} at {@code at}. */
	public static StatusEvent of(String id, Check check, Kind kind, CheckState state, Instant at) {
		DownReason reason = (kind == Kind.DOWN) ? state.downReason() : null;
		Instant runAt = (reason == null) ? null : switch (reason) {
			case MISSED -> state.nextDueAt();
			case RUNNING_TOO_LONG -> state.runStartedAt();
			case FAILED -> null;
		};
		return new StatusEvent(id, check.id(), check.projectId(), kind, reason, runAt, at);
	}

}
