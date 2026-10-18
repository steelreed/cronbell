package com.steelreed.cronbell.server.checks.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.steelreed.cronbell.server.checks.DownReason;
import com.steelreed.cronbell.server.checks.StatusEvent;
import com.steelreed.cronbell.server.checks.StatusEvent.Kind;
import com.steelreed.cronbell.server.checks.internal.domain.StatusTimeline.Day;
import com.steelreed.cronbell.server.checks.internal.domain.StatusTimeline.Span;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatusTimelineTests {

	static final Instant FROM = Instant.parse("2026-01-05T00:00:00Z");

	static final Instant TO = Instant.parse("2026-01-06T00:00:00Z");

	static StatusEvent event(Kind kind, String at) {
		return new StatusEvent(at, "c1", "p1", kind, (kind == Kind.DOWN) ? DownReason.FAILED : null, null,
				Instant.parse(at));
	}

	@Test
	void withoutEventsTheCheckWasUp() {
		Span span = StatusTimeline.between(List.of(), FROM, TO);
		assertThat(span.up()).isEqualTo(Duration.ofDays(1));
		assertThat(span.uptime()).hasValue(1.0);
		assertThat(span.downAtEnd()).isFalse();
	}

	@Test
	void theLatestEventBeforeTheSpanGivesItsStartingStatus() {
		List<StatusEvent> events = List.of(event(Kind.UP, "2026-01-01T00:00:00Z"),
				event(Kind.DOWN, "2026-01-04T00:00:00Z"), event(Kind.UP, "2026-01-05T06:00:00Z"));
		Span span = StatusTimeline.between(events, FROM, TO);
		assertThat(span.down()).isEqualTo(Duration.ofHours(6));
		assertThat(span.up()).isEqualTo(Duration.ofHours(18));
		assertThat(span.uptime()).hasValue(0.75);
	}

	@Test
	void pausedTimeIsLeftOut() {
		List<StatusEvent> events = List.of(event(Kind.DOWN, "2026-01-05T06:00:00Z"),
				event(Kind.PAUSED, "2026-01-05T12:00:00Z"), event(Kind.RESUMED, "2026-01-05T18:00:00Z"));
		Span span = StatusTimeline.between(events, FROM, TO);
		assertThat(span.paused()).isEqualTo(Duration.ofHours(6));
		assertThat(span.uptime()).hasValue(12.0 / 18);
	}

	@Test
	void eventsAfterTheSpanAreIgnored() {
		List<StatusEvent> events = List.of(event(Kind.DOWN, "2026-01-05T18:00:00Z"),
				event(Kind.UP, "2026-01-06T06:00:00Z"));
		Span span = StatusTimeline.between(events, FROM, TO);
		assertThat(span.down()).isEqualTo(Duration.ofHours(6));
		assertThat(span.downAtEnd()).isTrue();
	}

	@Test
	void anEmptyOrOnlyPausedSpanHasNoUptime() {
		assertThat(StatusTimeline.between(List.of(), TO, TO).uptime()).isEmpty();
		assertThat(StatusTimeline.between(List.of(event(Kind.PAUSED, "2026-01-04T00:00:00Z")), FROM, TO).uptime())
			.isEmpty();
	}

	@Test
	void daysAreClassifiedWorstFirst() {
		Span down = StatusTimeline.between(List.of(event(Kind.DOWN, "2026-01-05T23:00:00Z")), FROM, TO);
		Span recovered = StatusTimeline.between(
				List.of(event(Kind.DOWN, "2026-01-05T01:00:00Z"), event(Kind.UP, "2026-01-05T02:00:00Z")), FROM, TO);
		Span up = StatusTimeline.between(List.of(), FROM, TO);
		assertThat(down.day(5, 0)).isEqualTo(Day.DOWN);
		assertThat(recovered.day(5, 0)).isEqualTo(Day.ISSUES);
		assertThat(up.day(5, 1)).isEqualTo(Day.ISSUES);
		assertThat(up.day(5, 0)).isEqualTo(Day.OK);
		assertThat(up.day(0, 0)).isEqualTo(Day.NONE);
	}

}
