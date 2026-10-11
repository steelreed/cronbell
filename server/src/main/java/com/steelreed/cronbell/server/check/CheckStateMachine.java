package com.steelreed.cronbell.server.check;

import java.time.Duration;
import java.time.Instant;

import com.steelreed.cronbell.server.schedule.CronSchedule;
import com.steelreed.cronbell.server.schedule.Schedule;

/**
 * State transitions of DESIGN.md §3 and §4. Pure: no clock, no database.
 */
public final class CheckStateMachine {

	/**
	 * How early a success or failure may arrive and still count as the run due at the
	 * next cron time (clock skew between the job host and the server).
	 */
	public static final Duration EARLY_TOLERANCE = Duration.ofSeconds(60);

	private CheckStateMachine() {
	}

	/** What a transition means for alerting. */
	public enum Change {

		NONE, WENT_DOWN, RECOVERED

	}

	public record Outcome(CheckState state, Change change) {

		static Outcome unchanged(CheckState state) {
			return new Outcome(state, Change.NONE);
		}

	}

	/**
	 * Applies a ping received at {@code at}. Log pings and pings older than the last
	 * recorded one leave the state unchanged.
	 */
	public static Outcome onPing(CheckState s, Schedule schedule, PingKind kind, String rid, Instant at) {
		if (kind == PingKind.LOG || (s.lastPingAt() != null && at.isBefore(s.lastPingAt()))) {
			return Outcome.unchanged(s);
		}
		// NEW and PAUSED behave as UP from the first ping on (§3).
		boolean resuming = s.status() == CheckStatus.NEW || s.status() == CheckStatus.PAUSED;
		Instant currentDue = resuming ? null : s.nextDueAt();
		return switch (kind) {
			case START -> {
				CheckStatus status = resuming ? CheckStatus.UP : s.status();
				DownReason reason = status == CheckStatus.DOWN ? s.downReason() : null;
				Instant due = (currentDue != null) ? currentDue : schedule.next(at);
				yield Outcome.unchanged(new CheckState(status, reason, at, at, s.lastSuccessAt(), due, at, rid));
			}
			case SUCCESS -> {
				CheckState next = finishRun(s, schedule, rid, at, currentDue, CheckStatus.UP, null, at);
				yield new Outcome(next, s.status() == CheckStatus.DOWN ? Change.RECOVERED : Change.NONE);
			}
			case FAIL -> {
				CheckState next = finishRun(s, schedule, rid, at, currentDue, CheckStatus.DOWN, DownReason.FAILED,
						s.lastSuccessAt());
				yield new Outcome(next, s.status() == CheckStatus.DOWN ? Change.NONE : Change.WENT_DOWN);
			}
			case LOG -> throw new IllegalStateException("unreachable");
		};
	}

	private static CheckState finishRun(CheckState s, Schedule schedule, String rid, Instant at, Instant currentDue,
			CheckStatus status, DownReason reason, Instant lastSuccessAt) {
		// A finish with a different rid belongs to an older overlapping run: the newer
		// one stays open.
		boolean closesRun = s.runRid() == null || rid == null || rid.equals(s.runRid());
		Instant runStartedAt = closesRun ? null : s.runStartedAt();
		String runRid = closesRun ? null : s.runRid();
		Instant due = nextDueAfterRun(schedule, at, currentDue);
		return new CheckState(status, reason, at, s.lastStartAt(), lastSuccessAt, due, runStartedAt, runRid);
	}

	/**
	 * Next due time after a run completed at {@code at}. For cron schedules, a run that
	 * completes up to {@link #EARLY_TOLERANCE} before {@code currentDue} counts as that
	 * run. For periods the next run is always one period after this one, so an early run
	 * moves the due time forward.
	 */
	static Instant nextDueAfterRun(Schedule schedule, Instant at, Instant currentDue) {
		if (schedule instanceof CronSchedule && currentDue != null && at.isBefore(currentDue)
				&& !at.isBefore(currentDue.minus(EARLY_TOLERANCE))) {
			return schedule.next(currentDue);
		}
		return schedule.next(at);
	}

	/**
	 * Evaluates a check at {@code now} for the detector: MISSED or RUNNING_TOO_LONG. Only
	 * UP checks can go DOWN here; NEW and PAUSED never alert, DOWN stays DOWN.
	 */
	public static Outcome detect(CheckState s, Duration grace, Duration maxRuntime, Instant now) {
		if (s.status() != CheckStatus.UP) {
			return Outcome.unchanged(s);
		}
		if (s.runStartedAt() != null && now.isAfter(s.runStartedAt().plus(maxRuntime))) {
			return new Outcome(s.withStatus(CheckStatus.DOWN, DownReason.RUNNING_TOO_LONG), Change.WENT_DOWN);
		}
		if (s.nextDueAt() != null && now.isAfter(s.nextDueAt().plus(grace))
				&& (s.lastPingAt() == null || s.lastPingAt().isBefore(s.nextDueAt()))) {
			return new Outcome(s.withStatus(CheckStatus.DOWN, DownReason.MISSED), Change.WENT_DOWN);
		}
		return Outcome.unchanged(s);
	}

}
