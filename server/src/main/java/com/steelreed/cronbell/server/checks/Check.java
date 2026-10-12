package com.steelreed.cronbell.server.checks;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/** One job being watched. */
public record Check(String id, UUID pingKey, String projectId, String slug, String name, ScheduleKind scheduleKind,
		String cronExpression, ZoneId timeZone, Long periodSeconds, long graceSeconds, Long maxRuntimeSeconds,
		CheckState state, Instant createdAt) {

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
