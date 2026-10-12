package com.steelreed.cronbell.server.checks.internal.domain;

import java.time.Duration;
import java.time.Instant;

import com.steelreed.cronbell.server.checks.Check;

/** When a check is expected to run. */
public sealed interface Schedule permits CronSchedule, PeriodSchedule {

	/** The first expected run strictly after {@code after}. */
	Instant next(Instant after);

	/** The schedule a check is defined with. */
	static Schedule of(Check check) {
		return switch (check.scheduleKind()) {
			case CRON -> CronSchedule.parse(check.cronExpression(), check.timeZone());
			case PERIOD -> new PeriodSchedule(Duration.ofSeconds(check.periodSeconds()));
		};
	}

}
