package com.steelreed.cronbell.server.checks.web;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.steelreed.cronbell.server.checks.internal.domain.CronSchedule;
import com.steelreed.cronbell.server.shared.web.ManagementApi;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Previews a cron schedule before a check is saved (DESIGN.md §6): the next runs,
 * computed by the same code as the detector, so the form shows exactly what the server
 * will expect.
 */
@RestController
@ManagementApi
@RequestMapping("/api/v1/schedules")
class SchedulesController {

	static final int MAX_COUNT = 10;

	private final Clock clock;

	SchedulesController(Clock clock) {
		this.clock = clock;
	}

	@GetMapping("/next")
	NextRuns next(@RequestParam String cron, @RequestParam(defaultValue = "UTC") String timeZone,
			@RequestParam(defaultValue = "5") int count) {
		if (count < 1 || count > MAX_COUNT) {
			throw new IllegalArgumentException("count must be 1 to " + MAX_COUNT);
		}
		ZoneId zone;
		try {
			zone = ZoneId.of(timeZone.trim());
		}
		catch (DateTimeException ex) {
			throw new IllegalArgumentException("unknown time zone: " + timeZone);
		}
		CronSchedule schedule = CronSchedule.parse(cron, zone);
		List<Instant> runs = new ArrayList<>(count);
		Instant cursor = this.clock.instant();
		try {
			for (int i = 0; i < count; i++) {
				cursor = schedule.next(cursor);
				runs.add(cursor);
			}
		}
		catch (IllegalStateException ex) {
			// A valid expression that never fires, such as 30 February.
			throw new IllegalArgumentException(ex.getMessage());
		}
		return new NextRuns(schedule.expression(), zone.getId(), runs);
	}

	record NextRuns(String cron, String timeZone, List<Instant> runs) {
	}

}
