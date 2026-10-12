package com.steelreed.cronbell.server.pings.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.steelreed.cronbell.server.accounts.ProjectPrincipal;
import com.steelreed.cronbell.server.checks.Check;
import com.steelreed.cronbell.server.checks.CheckService;
import com.steelreed.cronbell.server.checks.PingKind;
import com.steelreed.cronbell.server.pings.Ping;
import com.steelreed.cronbell.server.pings.internal.PingRepository;
import com.steelreed.cronbell.server.shared.web.ManagementApi;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Recent pings of a check in the management API (DESIGN.md §6). */
@RestController
@ManagementApi
@RequestMapping("/api/v1/checks/{slug}/pings")
class PingsController {

	static final int DEFAULT_PING_LIMIT = 50;

	static final int MAX_PING_LIMIT = 200;

	private final CheckService checks;

	private final PingRepository pings;

	PingsController(CheckService checks, PingRepository pings) {
		this.checks = checks;
		this.pings = pings;
	}

	/** Latest pings, newest first, with run durations where the start is in the list. */
	@GetMapping
	List<PingResponse> pings(@AuthenticationPrincipal ProjectPrincipal project, @PathVariable String slug,
			@RequestParam(defaultValue = "" + DEFAULT_PING_LIMIT) int limit) {
		if (limit < 1 || limit > MAX_PING_LIMIT) {
			throw new IllegalArgumentException("limit must be 1 to " + MAX_PING_LIMIT);
		}
		Check check = this.checks.get(project.projectId(), slug);
		return PingResponse.of(this.pings.findRecent(check.id(), limit));
	}

	record PingResponse(String id, PingKind kind, Integer exitCode, String rid, String body, Instant receivedAt,
			String remoteAddr, String userAgent, Long durationMs) {

		/**
		 * Pairs each {@code success}/{@code fail} with its {@code start}: the one with
		 * the same rid, else the latest start not yet closed.
		 * @param newestFirst pings, newest first
		 */
		static List<PingResponse> of(List<Ping> newestFirst) {
			List<Ping> open = new ArrayList<>();
			List<PingResponse> result = new ArrayList<>(newestFirst.size());
			for (Ping ping : newestFirst.reversed()) {
				Long duration = null;
				if (ping.kind() == PingKind.START) {
					open.add(ping);
				}
				else if (ping.kind() != PingKind.LOG && !open.isEmpty()) {
					Ping start = open.stream()
						.filter((candidate) -> ping.rid() != null && ping.rid().equals(candidate.rid()))
						.findFirst()
						.orElse(open.getLast());
					open.remove(start);
					duration = Duration.between(start.receivedAt(), ping.receivedAt()).toMillis();
				}
				result.add(new PingResponse(ping.id(), ping.kind(), ping.exitCode(), ping.rid(), ping.body(),
						ping.receivedAt(), ping.remoteAddr(), ping.userAgent(), duration));
			}
			return result.reversed();
		}

	}

}
