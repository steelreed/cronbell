package com.steelreed.cronbell.server.check;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.steelreed.cronbell.server.check.CheckStateMachine.Change;
import com.steelreed.cronbell.server.check.CheckStateMachine.Outcome;
import com.steelreed.cronbell.server.schedule.CronSchedule;
import com.steelreed.cronbell.server.schedule.PeriodSchedule;
import com.steelreed.cronbell.server.schedule.Schedule;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckStateMachineTests {

	private static final Schedule HOURLY = CronSchedule.parse("0 * * * *", ZoneOffset.UTC);

	private static final Schedule PERIOD = new PeriodSchedule(Duration.ofHours(1));

	private static final Duration GRACE = Duration.ofMinutes(5);

	private static final Duration MAX_RUNTIME = Duration.ofMinutes(10);

	private static Instant at(String time) {
		return Instant.parse("2026-01-05T" + time + "Z");
	}

	private static Outcome ping(CheckState s, PingKind kind, String time) {
		return CheckStateMachine.onPing(s, HOURLY, kind, null, at(time));
	}

	private static CheckState withDue(CheckState s, String nextDue) {
		return new CheckState(s.status(), s.downReason(), s.lastPingAt(), s.lastStartAt(), s.lastSuccessAt(),
				at(nextDue), s.runStartedAt(), s.runRid());
	}

	@Nested
	class Pings {

		@Test
		void firstSuccessMovesNewToUpWithoutEvent() {
			Outcome outcome = ping(CheckState.initial(), PingKind.SUCCESS, "10:00:05");
			assertThat(outcome.change()).isEqualTo(Change.NONE);
			assertThat(outcome.state().status()).isEqualTo(CheckStatus.UP);
			assertThat(outcome.state().lastSuccessAt()).isEqualTo(at("10:00:05"));
			assertThat(outcome.state().lastPingAt()).isEqualTo(at("10:00:05"));
			assertThat(outcome.state().nextDueAt()).isEqualTo(at("11:00:00"));
		}

		@Test
		void firstStartMovesNewToUpAndOpensARun() {
			Outcome outcome = CheckStateMachine.onPing(CheckState.initial(), HOURLY, PingKind.START, "r1",
					at("10:00:01"));
			CheckState s = outcome.state();
			assertThat(s.status()).isEqualTo(CheckStatus.UP);
			assertThat(s.lastStartAt()).isEqualTo(at("10:00:01"));
			assertThat(s.runStartedAt()).isEqualTo(at("10:00:01"));
			assertThat(s.runRid()).isEqualTo("r1");
			assertThat(s.nextDueAt()).isEqualTo(at("11:00:00"));
			assertThat(s.lastSuccessAt()).isNull();
		}

		@Test
		void successClosesTheRun() {
			CheckState started = ping(CheckState.initial(), PingKind.START, "10:00:01").state();
			CheckState done = ping(started, PingKind.SUCCESS, "10:03:00").state();
			assertThat(done.runStartedAt()).isNull();
			assertThat(done.lastStartAt()).isEqualTo(at("10:00:01"));
			assertThat(done.nextDueAt()).isEqualTo(at("11:00:00"));
		}

		@Test
		void finishWithAnotherRidLeavesTheNewerRunOpen() {
			CheckState first = CheckStateMachine
				.onPing(CheckState.initial(), HOURLY, PingKind.START, "a", at("10:00:00"))
				.state();
			CheckState second = CheckStateMachine.onPing(first, HOURLY, PingKind.START, "b", at("10:30:00")).state();
			CheckState firstDone = CheckStateMachine.onPing(second, HOURLY, PingKind.SUCCESS, "a", at("10:40:00"))
				.state();
			assertThat(firstDone.runStartedAt()).isEqualTo(at("10:30:00"));
			assertThat(firstDone.runRid()).isEqualTo("b");
			CheckState secondDone = CheckStateMachine.onPing(firstDone, HOURLY, PingKind.SUCCESS, "b", at("10:50:00"))
				.state();
			assertThat(secondDone.runStartedAt()).isNull();
		}

		@Test
		void failGoesDownWithEvent() {
			CheckState up = ping(CheckState.initial(), PingKind.SUCCESS, "09:00:05").state();
			Outcome outcome = ping(up, PingKind.FAIL, "10:00:05");
			assertThat(outcome.change()).isEqualTo(Change.WENT_DOWN);
			assertThat(outcome.state().status()).isEqualTo(CheckStatus.DOWN);
			assertThat(outcome.state().downReason()).isEqualTo(DownReason.FAILED);
			assertThat(outcome.state().lastSuccessAt()).isEqualTo(at("09:00:05"));
			assertThat(outcome.state().nextDueAt()).isEqualTo(at("11:00:00"));
		}

		@Test
		void failOnNewCheckGoesDownWithEvent() {
			Outcome outcome = ping(CheckState.initial(), PingKind.FAIL, "10:00:05");
			assertThat(outcome.change()).isEqualTo(Change.WENT_DOWN);
			assertThat(outcome.state().downReason()).isEqualTo(DownReason.FAILED);
		}

		@Test
		void repeatedFailureDoesNotAlertAgain() {
			CheckState down = ping(CheckState.initial(), PingKind.FAIL, "10:00:05").state();
			Outcome outcome = ping(down, PingKind.FAIL, "11:00:05");
			assertThat(outcome.change()).isEqualTo(Change.NONE);
			assertThat(outcome.state().status()).isEqualTo(CheckStatus.DOWN);
		}

		@Test
		void successAfterDownRecovers() {
			CheckState down = ping(CheckState.initial(), PingKind.FAIL, "10:00:05").state();
			Outcome outcome = ping(down, PingKind.SUCCESS, "11:00:05");
			assertThat(outcome.change()).isEqualTo(Change.RECOVERED);
			assertThat(outcome.state().status()).isEqualTo(CheckStatus.UP);
			assertThat(outcome.state().downReason()).isNull();
		}

		@Test
		void startWhileDownStaysDown() {
			CheckState down = ping(CheckState.initial(), PingKind.FAIL, "10:00:05").state();
			Outcome outcome = ping(down, PingKind.START, "11:00:00");
			assertThat(outcome.change()).isEqualTo(Change.NONE);
			assertThat(outcome.state().status()).isEqualTo(CheckStatus.DOWN);
			assertThat(outcome.state().downReason()).isEqualTo(DownReason.FAILED);
			assertThat(outcome.state().runStartedAt()).isEqualTo(at("11:00:00"));
		}

		@Test
		void pausedReturnsToUpOnNextPing() {
			CheckState paused = ping(CheckState.initial(), PingKind.SUCCESS, "09:00:05").state()
				.withStatus(CheckStatus.PAUSED, null);
			Outcome outcome = ping(paused, PingKind.SUCCESS, "15:20:00");
			assertThat(outcome.change()).isEqualTo(Change.NONE);
			assertThat(outcome.state().status()).isEqualTo(CheckStatus.UP);
			assertThat(outcome.state().nextDueAt()).isEqualTo(at("16:00:00"));
		}

		@Test
		void logChangesNothing() {
			CheckState up = ping(CheckState.initial(), PingKind.SUCCESS, "10:00:05").state();
			Outcome outcome = ping(up, PingKind.LOG, "10:30:00");
			assertThat(outcome.state()).isEqualTo(up);
			assertThat(outcome.change()).isEqualTo(Change.NONE);
		}

		@Test
		void stalePingChangesNothing() {
			CheckState up = ping(CheckState.initial(), PingKind.SUCCESS, "10:00:05").state();
			Outcome outcome = ping(up, PingKind.FAIL, "10:00:04");
			assertThat(outcome.state()).isEqualTo(up);
			assertThat(outcome.change()).isEqualTo(Change.NONE);
		}

		@Test
		void pingAtTheSameInstantIsNotStale() {
			CheckState up = ping(CheckState.initial(), PingKind.SUCCESS, "10:00:05").state();
			assertThat(ping(up, PingKind.FAIL, "10:00:05").state().status()).isEqualTo(CheckStatus.DOWN);
		}

		@Test
		void slightlyEarlyCronRunCountsForTheDueRun() {
			CheckState up = ping(CheckState.initial(), PingKind.SUCCESS, "10:00:05").state();
			// Due 11:00; the job's clock is 30 s ahead.
			CheckState early = ping(up, PingKind.SUCCESS, "10:59:30").state();
			assertThat(early.nextDueAt()).isEqualTo(at("12:00:00"));
		}

		@Test
		void muchEarlierCronRunDoesNotSkipTheDueRun() {
			CheckState up = ping(CheckState.initial(), PingKind.SUCCESS, "10:00:05").state();
			// A manual run in the middle of the hour: 11:00 is still expected.
			CheckState manual = ping(up, PingKind.SUCCESS, "10:30:00").state();
			assertThat(manual.nextDueAt()).isEqualTo(at("11:00:00"));
		}

		@Test
		void earlyPeriodRunMovesDueForward() {
			CheckState up = CheckStateMachine
				.onPing(CheckState.initial(), PERIOD, PingKind.SUCCESS, null, at("10:00:00"))
				.state();
			assertThat(up.nextDueAt()).isEqualTo(at("11:00:00"));
			CheckState early = CheckStateMachine.onPing(up, PERIOD, PingKind.SUCCESS, null, at("10:20:00")).state();
			assertThat(early.nextDueAt()).isEqualTo(at("11:20:00"));
		}

	}

	@Nested
	class Detection {

		private Outcome detect(CheckState s, String now) {
			return CheckStateMachine.detect(s, GRACE, MAX_RUNTIME, at(now));
		}

		@Test
		void upWithinGraceStaysUp() {
			CheckState up = withDue(ping(CheckState.initial(), PingKind.SUCCESS, "10:00:05").state(), "11:00:00");
			assertThat(detect(up, "11:05:00").change()).isEqualTo(Change.NONE);
		}

		@Test
		void missedAfterDuePlusGrace() {
			CheckState up = ping(CheckState.initial(), PingKind.SUCCESS, "10:00:05").state();
			Outcome outcome = detect(up, "11:05:01");
			assertThat(outcome.change()).isEqualTo(Change.WENT_DOWN);
			assertThat(outcome.state().status()).isEqualTo(CheckStatus.DOWN);
			assertThat(outcome.state().downReason()).isEqualTo(DownReason.MISSED);
			assertThat(outcome.state().nextDueAt()).isEqualTo(at("11:00:00"));
		}

		@Test
		void startAfterDueIsNotMissed() {
			CheckState up = ping(CheckState.initial(), PingKind.SUCCESS, "10:00:05").state();
			CheckState running = ping(up, PingKind.START, "11:00:01").state();
			assertThat(detect(running, "11:06:00").change()).isEqualTo(Change.NONE);
		}

		@Test
		void runningTooLong() {
			CheckState running = ping(CheckState.initial(), PingKind.START, "10:00:00").state();
			assertThat(detect(running, "10:10:00").change()).isEqualTo(Change.NONE);
			Outcome outcome = detect(running, "10:10:01");
			assertThat(outcome.change()).isEqualTo(Change.WENT_DOWN);
			assertThat(outcome.state().downReason()).isEqualTo(DownReason.RUNNING_TOO_LONG);
		}

		@Test
		void newPausedAndDownChecksAreLeftAlone() {
			assertThat(detect(CheckState.initial(), "23:00:00").change()).isEqualTo(Change.NONE);
			CheckState up = ping(CheckState.initial(), PingKind.SUCCESS, "10:00:05").state();
			assertThat(detect(up.withStatus(CheckStatus.PAUSED, null), "23:00:00").change()).isEqualTo(Change.NONE);
			assertThat(detect(up.withStatus(CheckStatus.DOWN, DownReason.FAILED), "23:00:00").change())
				.isEqualTo(Change.NONE);
		}

		@Test
		void missedThenSuccessRecovers() {
			CheckState up = ping(CheckState.initial(), PingKind.SUCCESS, "10:00:05").state();
			CheckState missed = detect(up, "11:10:00").state();
			Outcome recovered = ping(missed, PingKind.SUCCESS, "11:12:00");
			assertThat(recovered.change()).isEqualTo(Change.RECOVERED);
			assertThat(recovered.state().nextDueAt()).isEqualTo(at("12:00:00"));
		}

	}

}
