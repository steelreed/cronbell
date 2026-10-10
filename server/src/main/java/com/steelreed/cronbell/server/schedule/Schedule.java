package com.steelreed.cronbell.server.schedule;

import java.time.Instant;

/** When a check is expected to run. */
public sealed interface Schedule permits CronSchedule, PeriodSchedule {

	/** The first expected run strictly after {@code after}. */
	Instant next(Instant after);

}
