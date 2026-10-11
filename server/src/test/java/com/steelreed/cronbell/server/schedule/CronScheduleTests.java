package com.steelreed.cronbell.server.schedule;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CronScheduleTests {

	private static final ZoneId UTC = ZoneOffset.UTC;

	private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

	private static Instant at(String iso) {
		return Instant.parse(iso);
	}

	@Test
	void fiveFieldExpressionRunsAtSecondZero() {
		CronSchedule schedule = CronSchedule.parse("*/5 * * * *", UTC);
		assertThat(schedule.next(at("2026-01-05T10:02:30Z"))).isEqualTo(at("2026-01-05T10:05:00Z"));
	}

	@Test
	void nextIsStrictlyAfter() {
		CronSchedule schedule = CronSchedule.parse("*/5 * * * *", UTC);
		assertThat(schedule.next(at("2026-01-05T10:05:00Z"))).isEqualTo(at("2026-01-05T10:10:00Z"));
	}

	@Test
	void sixFieldExpressionHasSeconds() {
		CronSchedule schedule = CronSchedule.parse("30 */5 * * * *", UTC);
		assertThat(schedule.next(at("2026-01-05T10:02:00Z"))).isEqualTo(at("2026-01-05T10:05:30Z"));
	}

	@Test
	void fiveAndSixFieldFormsAgree() {
		Instant after = at("2026-01-05T10:02:00Z");
		assertThat(CronSchedule.parse("15 3 * * 1-5", UTC).next(after))
			.isEqualTo(CronSchedule.parse("0 15 3 * * 1-5", UTC).next(after))
			.isEqualTo(at("2026-01-06T03:15:00Z"));
	}

	@Test
	void macros() {
		assertThat(CronSchedule.parse("@daily", UTC).next(at("2026-01-05T10:00:00Z")))
			.isEqualTo(at("2026-01-06T00:00:00Z"));
		assertThat(CronSchedule.parse("@hourly", UTC).next(at("2026-01-05T10:00:00Z")))
			.isEqualTo(at("2026-01-05T11:00:00Z"));
	}

	@Test
	void whitespaceIsNormalised() {
		CronSchedule schedule = CronSchedule.parse("  0   3 * *  * ", UTC);
		assertThat(schedule.expression()).isEqualTo("0 3 * * *");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "* * * *", "0 0 0 * * * *", "daily", "61 * * * *", "* * * * * * 2026", "@nope" })
	void rejectsInvalidExpressions(String expression) {
		assertThatIllegalArgumentException().isThrownBy(() -> CronSchedule.parse(expression, UTC));
	}

	@Test
	void evaluatesInTheTimeZone() {
		CronSchedule schedule = CronSchedule.parse("0 9 * * *", ZoneId.of("Asia/Ho_Chi_Minh"));
		assertThat(schedule.next(at("2026-01-05T00:00:00Z"))).isEqualTo(at("2026-01-05T02:00:00Z"));
	}

	@Test
	void runInASkippedHourIsDueAtTheEndOfTheGap() {
		// Berlin, 2026-03-29: 02:00 CET jumps to 03:00 CEST (01:00Z). 02:30 does not
		// exist.
		CronSchedule schedule = CronSchedule.parse("30 2 * * *", BERLIN);
		Instant gapRun = schedule.next(at("2026-03-28T23:00:00Z"));
		assertThat(gapRun).isEqualTo(at("2026-03-29T01:00:00Z"));
		assertThat(schedule.next(gapRun)).isEqualTo(at("2026-03-30T00:30:00Z"));
	}

	@Test
	void skippedHourInNewYork() {
		// New York, 2026-03-08: 02:00 EST jumps to 03:00 EDT (07:00Z).
		CronSchedule schedule = CronSchedule.parse("15 2 * * *", NEW_YORK);
		assertThat(schedule.next(at("2026-03-08T05:00:00Z"))).isEqualTo(at("2026-03-08T07:00:00Z"));
	}

	@Test
	void everyMinuteThroughAGapCollapsesToOneRun() {
		CronSchedule schedule = CronSchedule.parse("*/30 * * * *", BERLIN);
		// 01:30 CET -> 02:00 and 02:30 do not exist -> 03:00 CEST.
		Instant first = schedule.next(at("2026-03-29T00:30:00Z"));
		assertThat(first).isEqualTo(at("2026-03-29T01:00:00Z"));
		assertThat(schedule.next(first)).isEqualTo(at("2026-03-29T01:30:00Z"));
	}

	@Test
	void runInARepeatedHourIsDueOnceAtItsFirstOccurrence() {
		// Berlin, 2026-10-25: 03:00 CEST falls back to 02:00 CET (01:00Z). 02:30 happens
		// twice.
		CronSchedule schedule = CronSchedule.parse("30 2 * * *", BERLIN);
		Instant first = schedule.next(at("2026-10-24T22:00:00Z"));
		assertThat(first).isEqualTo(at("2026-10-25T00:30:00Z"));
		assertThat(schedule.next(first)).isEqualTo(at("2026-10-26T01:30:00Z"));
	}

	@Test
	void afterTheSecondOccurrenceStartsTheNextRunIsLater() {
		CronSchedule schedule = CronSchedule.parse("45 2 * * *", BERLIN);
		// 02:30 CET (second occurrence, 01:30Z): 02:45 CET is 01:45Z, after it.
		assertThat(schedule.next(at("2026-10-25T01:30:00Z"))).isEqualTo(at("2026-10-25T01:45:00Z"));
	}

	@Test
	void equality() {
		assertThat(CronSchedule.parse("0 3 * * *", UTC)).isEqualTo(CronSchedule.parse("0  3 * * *", UTC))
			.isNotEqualTo(CronSchedule.parse("0 3 * * *", BERLIN));
	}

}
