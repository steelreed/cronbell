package com.steelreed.cronbell.server.checks.web;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.steelreed.cronbell.server.accounts.ProjectPrincipal;
import com.steelreed.cronbell.server.checks.Check;
import com.steelreed.cronbell.server.checks.CheckService;
import com.steelreed.cronbell.server.checks.DownReason;
import com.steelreed.cronbell.server.checks.RunStats;
import com.steelreed.cronbell.server.checks.StatusEvent;
import com.steelreed.cronbell.server.checks.internal.StatusEventRepository;
import com.steelreed.cronbell.server.checks.internal.domain.StatusTimeline;
import com.steelreed.cronbell.server.shared.web.ManagementApi;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The status history (DESIGN.md §6): the status changes of a check or of the whole
 * project, and how each check spent its last days, for the overview's status strip.
 */
@RestController
@ManagementApi
class StatusHistoryController {

	static final int DEFAULT_EVENTS = 50;

	static final int MAX_EVENTS = 200;

	static final int DEFAULT_DAYS = 30;

	static final int MAX_DAYS = 90;

	private final CheckService checks;

	private final StatusEventRepository history;

	private final RunStats runs;

	private final Clock clock;

	StatusHistoryController(CheckService checks, StatusEventRepository history, RunStats runs, Clock clock) {
		this.checks = checks;
		this.history = history;
		this.runs = runs;
		this.clock = clock;
	}

	/** The latest status changes of a check, newest first. */
	@GetMapping("/api/v1/checks/{slug}/events")
	List<EventResponse> checkEvents(@AuthenticationPrincipal ProjectPrincipal project, @PathVariable String slug,
			@RequestParam(defaultValue = "" + DEFAULT_EVENTS) int limit) {
		checkLimit(limit);
		Check check = this.checks.get(project.projectId(), slug);
		return this.history.findRecentByCheck(check.id(), limit)
			.stream()
			.map((event) -> EventResponse.of(event, check))
			.toList();
	}

	/** The latest status changes of every check of the project, newest first. */
	@GetMapping("/api/v1/events")
	List<EventResponse> projectEvents(@AuthenticationPrincipal ProjectPrincipal project,
			@RequestParam(defaultValue = "" + DEFAULT_EVENTS) int limit) {
		checkLimit(limit);
		Map<String, Check> checks = this.checks.list(project.projectId())
			.stream()
			.collect(Collectors.toMap(Check::id, (check) -> check));
		return this.history.findRecentByProject(project.projectId(), limit)
			.stream()
			.filter((event) -> checks.containsKey(event.checkId()))
			.map((event) -> EventResponse.of(event, checks.get(event.checkId())))
			.toList();
	}

	/**
	 * Every check with how it spent each calendar day in {@code tz}, the last
	 * {@code days} days up to and including today, oldest first. Today counts up to now;
	 * days before the check was created have no data.
	 */
	@GetMapping("/api/v1/stats/status")
	List<CheckDaysResponse> statusDays(@AuthenticationPrincipal ProjectPrincipal project,
			@RequestParam(defaultValue = "" + DEFAULT_DAYS) int days, @RequestParam(defaultValue = "UTC") String tz) {
		if (days < 1 || days > MAX_DAYS) {
			throw new IllegalArgumentException("days must be 1 to " + MAX_DAYS);
		}
		ZoneId zone = zone(tz);
		Instant now = this.clock.instant();
		LocalDate today = LocalDate.ofInstant(now, zone);
		List<LocalDate> dates = today.minusDays(days - 1L).datesUntil(today.plusDays(1)).toList();
		List<Instant> boundaries = new ArrayList<>(days + 1);
		for (LocalDate date : dates) {
			boundaries.add(date.atStartOfDay(zone).toInstant());
		}
		boundaries.add(today.plusDays(1).atStartOfDay(zone).toInstant());

		List<Check> checks = this.checks.list(project.projectId());
		Map<String, List<StatusEvent>> timelines = this.history
			.findTimelines(project.projectId(), boundaries.getFirst())
			.stream()
			.collect(Collectors.groupingBy(StatusEvent::checkId));
		Map<String, RunStats.Count> counts = new HashMap<>();
		for (RunStats.Count count : this.runs.countPerBucket(checks.stream().map(Check::id).toList(), boundaries)) {
			counts.put(count.checkId() + "/" + count.bucket(), count);
		}

		List<CheckDaysResponse> result = new ArrayList<>(checks.size());
		for (Check check : checks) {
			List<StatusEvent> timeline = timelines.getOrDefault(check.id(), List.of());
			List<DayResponse> checkDays = new ArrayList<>(days);
			for (int i = 0; i < days; i++) {
				Instant start = boundaries.get(i);
				Instant end = boundaries.get(i + 1);
				Instant from = check.createdAt().isAfter(start) ? check.createdAt() : start;
				Instant to = now.isBefore(end) ? now : end;
				StatusTimeline.Span span = StatusTimeline.between(timeline, from, to);
				RunStats.Count count = counts.get(check.id() + "/" + i);
				long runs = (count != null) ? count.runs() : 0;
				long failures = (count != null) ? count.failures() : 0;
				long missed = timeline.stream()
					.filter((event) -> event.reason() == DownReason.MISSED && !event.at().isBefore(start)
							&& event.at().isBefore(end))
					.count();
				checkDays.add(new DayResponse(dates.get(i), span.day(runs, failures), runs, failures, missed,
						span.down().toMillis()));
			}
			result.add(new CheckDaysResponse(check.slug(), check.name(), checkDays));
		}
		return result;
	}

	private static void checkLimit(int limit) {
		if (limit < 1 || limit > MAX_EVENTS) {
			throw new IllegalArgumentException("limit must be 1 to " + MAX_EVENTS);
		}
	}

	private static ZoneId zone(String tz) {
		try {
			return ZoneId.of(tz.trim());
		}
		catch (DateTimeException ex) {
			throw new IllegalArgumentException("unknown time zone: " + tz);
		}
	}

	/**
	 * A status change: {@code kind} is the status entered, {@code reason} why a check
	 * went DOWN, {@code runAt} the run it is about (the one missed, or the one that ran
	 * too long).
	 */
	record EventResponse(String id, String slug, String name, StatusEvent.Kind kind, DownReason reason, Instant runAt,
			Instant at) {

		static EventResponse of(StatusEvent event, Check check) {
			return new EventResponse(event.id(), check.slug(), check.name(), event.kind(), event.reason(),
					event.runAt(), event.at());
		}

	}

	record CheckDaysResponse(String slug, String name, List<DayResponse> days) {
	}

	/**
	 * @param missed how many times the check went DOWN for a missed run that day
	 * @param downMs how long it was DOWN that day
	 */
	record DayResponse(LocalDate date, StatusTimeline.Day state, long runs, long failures, long missed, long downMs) {
	}

}
