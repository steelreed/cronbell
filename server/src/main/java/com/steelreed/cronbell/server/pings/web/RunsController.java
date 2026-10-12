package com.steelreed.cronbell.server.pings.web;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.steelreed.cronbell.server.accounts.ProjectPrincipal;
import com.steelreed.cronbell.server.checks.Check;
import com.steelreed.cronbell.server.checks.CheckService;
import com.steelreed.cronbell.server.checks.PingKind;
import com.steelreed.cronbell.server.pings.internal.PingRepository;
import com.steelreed.cronbell.server.pings.internal.PingRepository.BucketCount;
import com.steelreed.cronbell.server.shared.web.ManagementApi;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Finished runs as the dashboard summarises them (DESIGN.md §6): the latest outcomes of
 * every check, for the run bars of the checks list, and runs and failures per day, for
 * the overview. A run is a {@code success} or {@code fail} ping; a missed run leaves no
 * ping, so it is not counted here (the check's status says when the latest one was
 * missed).
 */
@RestController
@ManagementApi
class RunsController {

	static final int DEFAULT_RECENT = 20;

	static final int MAX_RECENT = 90;

	static final int DEFAULT_DAYS = 14;

	static final int MAX_DAYS = 90;

	private final CheckService checks;

	private final PingRepository pings;

	private final Clock clock;

	RunsController(CheckService checks, PingRepository pings, Clock clock) {
		this.checks = checks;
		this.pings = pings;
		this.clock = clock;
	}

	/** Slug to its latest finished runs, oldest first, for every check of the project. */
	@GetMapping("/api/v1/runs/recent")
	Map<String, List<RunResponse>> recent(@AuthenticationPrincipal ProjectPrincipal project,
			@RequestParam(defaultValue = "" + DEFAULT_RECENT) int limit) {
		if (limit < 1 || limit > MAX_RECENT) {
			throw new IllegalArgumentException("limit must be 1 to " + MAX_RECENT);
		}
		Map<String, List<RunResponse>> result = new LinkedHashMap<>();
		for (Check check : this.checks.list(project.projectId())) {
			List<RunResponse> runs = this.pings.findRecentFinished(check.id(), limit)
				.reversed()
				.stream()
				.map((run) -> new RunResponse(run.at(), run.kind() == PingKind.SUCCESS, run.exitCode()))
				.toList();
			result.put(check.slug(), runs);
		}
		return result;
	}

	/**
	 * Finished runs and failures per calendar day in {@code tz}, the last {@code days}
	 * days up to and including today, oldest first.
	 */
	@GetMapping("/api/v1/stats/daily")
	List<DayResponse> daily(@AuthenticationPrincipal ProjectPrincipal project,
			@RequestParam(defaultValue = "" + DEFAULT_DAYS) int days, @RequestParam(defaultValue = "UTC") String tz) {
		if (days < 1 || days > MAX_DAYS) {
			throw new IllegalArgumentException("days must be 1 to " + MAX_DAYS);
		}
		ZoneId zone = zone(tz);
		LocalDate today = LocalDate.ofInstant(this.clock.instant(), zone);
		List<LocalDate> dates = today.minusDays(days - 1L).datesUntil(today.plusDays(1)).toList();
		List<Instant> boundaries = new ArrayList<>(days + 1);
		for (LocalDate date : dates) {
			boundaries.add(date.atStartOfDay(zone).toInstant());
		}
		boundaries.add(today.plusDays(1).atStartOfDay(zone).toInstant());
		List<String> ids = this.checks.list(project.projectId()).stream().map(Check::id).toList();
		long[] runs = new long[days];
		long[] failures = new long[days];
		for (BucketCount count : this.pings.countFinishedByBucket(ids, boundaries)) {
			runs[count.bucket()] += count.count();
			if (count.kind() == PingKind.FAIL) {
				failures[count.bucket()] += count.count();
			}
		}
		List<DayResponse> result = new ArrayList<>(days);
		for (int i = 0; i < days; i++) {
			result.add(new DayResponse(dates.get(i), runs[i], failures[i]));
		}
		return result;
	}

	private static ZoneId zone(String tz) {
		try {
			return ZoneId.of(tz.trim());
		}
		catch (DateTimeException ex) {
			throw new IllegalArgumentException("unknown time zone: " + tz);
		}
	}

	record RunResponse(Instant at, boolean ok, Integer exitCode) {
	}

	record DayResponse(LocalDate date, long runs, long failures) {
	}

}
