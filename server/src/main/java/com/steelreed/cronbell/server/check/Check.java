package com.steelreed.cronbell.server.check;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import com.steelreed.cronbell.server.schedule.CronSchedule;
import com.steelreed.cronbell.server.schedule.PeriodSchedule;
import com.steelreed.cronbell.server.schedule.Schedule;

/** One job being watched. */
public record Check(String id, UUID pingKey, String projectId, String slug, String name, ScheduleKind scheduleKind,
		String cronExpression, ZoneId timeZone, Long periodSeconds, long graceSeconds, Long maxRuntimeSeconds,
		CheckState state, Instant createdAt) {

	public Schedule schedule() {
		return switch (this.scheduleKind) {
			case CRON -> CronSchedule.parse(this.cronExpression, this.timeZone);
			case PERIOD -> new PeriodSchedule(Duration.ofSeconds(this.periodSeconds));
		};
	}

	public Duration grace() {
		return Duration.ofSeconds(this.graceSeconds);
	}

	/** Defaults to the grace time (§4). */
	public Duration maxRuntime() {
		return (this.maxRuntimeSeconds != null) ? Duration.ofSeconds(this.maxRuntimeSeconds) : grace();
	}

	public Check withState(CheckState state) {
		return new Check(this.id, this.pingKey, this.projectId, this.slug, this.name, this.scheduleKind,
				this.cronExpression, this.timeZone, this.periodSeconds, this.graceSeconds, this.maxRuntimeSeconds,
				state, this.createdAt);
	}

}
