package com.steelreed.cronbell.server.checks.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** A run is expected every {@code period} after the previous one. */
public record PeriodSchedule(Duration period) implements Schedule {

	public PeriodSchedule {
		Objects.requireNonNull(period, "period");
		if (period.isNegative() || period.isZero()) {
			throw new IllegalArgumentException("period must be positive");
		}
	}

	@Override
	public Instant next(Instant after) {
		return after.plus(this.period);
	}

}
