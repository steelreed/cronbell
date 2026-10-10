package com.steelreed.cronbell.server.schedule;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PeriodScheduleTests {

	@Test
	void nextIsOnePeriodLater() {
		PeriodSchedule schedule = new PeriodSchedule(Duration.ofMinutes(10));
		assertThat(schedule.next(Instant.parse("2026-01-05T10:02:30Z"))).isEqualTo(Instant.parse("2026-01-05T10:12:30Z"));
	}

	@Test
	void ignoresDaylightSaving() {
		PeriodSchedule schedule = new PeriodSchedule(Duration.ofHours(1));
		assertThat(schedule.next(Instant.parse("2026-03-29T00:30:00Z"))).isEqualTo(Instant.parse("2026-03-29T01:30:00Z"));
	}

	@Test
	void periodMustBePositive() {
		assertThatIllegalArgumentException().isThrownBy(() -> new PeriodSchedule(Duration.ZERO));
		assertThatIllegalArgumentException().isThrownBy(() -> new PeriodSchedule(Duration.ofSeconds(-1)));
	}

}
