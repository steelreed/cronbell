package com.steelreed.cronbell.server.checks.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.steelreed.cronbell.server.accounts.ProjectPrincipal;
import com.steelreed.cronbell.server.checks.Check;
import com.steelreed.cronbell.server.checks.CheckService;
import com.steelreed.cronbell.server.checks.CheckStatus;
import com.steelreed.cronbell.server.checks.DownReason;
import com.steelreed.cronbell.server.checks.ScheduleKind;
import com.steelreed.cronbell.server.checks.internal.domain.Schedule;
import com.steelreed.cronbell.server.shared.web.ManagementApi;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Management API for checks (DESIGN.md §6). Scoped to the project of the API key. */
@RestController
@ManagementApi
@RequestMapping("/api/v1/checks")
class ChecksController {

	private final CheckService checks;

	private final Clock clock;

	ChecksController(CheckService checks, Clock clock) {
		this.checks = checks;
		this.clock = clock;
	}

	private CheckResponse response(Check check) {
		return CheckResponse.of(check, this.clock.instant());
	}

	@GetMapping
	List<CheckResponse> list(@AuthenticationPrincipal ProjectPrincipal project) {
		return this.checks.list(project.projectId()).stream().map(this::response).toList();
	}

	/**
	 * Creates or updates the check with this slug. 201 when created, 200 when updated.
	 */
	@PutMapping("/{slug}")
	ResponseEntity<CheckResponse> upsert(@AuthenticationPrincipal ProjectPrincipal project, @PathVariable String slug,
			@RequestBody CheckRequest request) {
		CheckService.Saved saved = this.checks.upsert(project.projectId(), slug, request.toDefinition());
		return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.body(response(saved.check()));
	}

	@GetMapping("/{slug}")
	CheckResponse get(@AuthenticationPrincipal ProjectPrincipal project, @PathVariable String slug) {
		return response(this.checks.get(project.projectId(), slug));
	}

	@DeleteMapping("/{slug}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal ProjectPrincipal project, @PathVariable String slug) {
		this.checks.delete(project.projectId(), slug);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{slug}/resume")
	CheckResponse resume(@AuthenticationPrincipal ProjectPrincipal project, @PathVariable String slug) {
		return response(this.checks.resume(project.projectId(), slug));
	}

	@PostMapping("/{slug}/pause")
	CheckResponse pause(@AuthenticationPrincipal ProjectPrincipal project, @PathVariable String slug) {
		return response(this.checks.pause(project.projectId(), slug));
	}

	record CheckRequest(String name, String cron, String timeZone, Long periodSeconds, Long graceSeconds,
			Long maxRuntimeSeconds) {

		CheckService.Definition toDefinition() {
			return new CheckService.Definition(this.name, this.cron, this.timeZone, this.periodSeconds,
					this.graceSeconds, this.maxRuntimeSeconds);
		}

	}

	record CheckResponse(String slug, String name, UUID pingKey, String pingUrl, ScheduleKind scheduleKind, String cron,
			String timeZone, Long periodSeconds, long graceSeconds, Long maxRuntimeSeconds, CheckStatus status,
			DownReason downReason, Instant lastPingAt, Instant lastStartAt, Instant lastSuccessAt, Instant nextDueAt,
			Instant nextRunAt, Instant createdAt) {

		static CheckResponse of(Check c, Instant now) {
			String pingUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
				.path("/ping/{key}")
				.buildAndExpand(c.pingKey())
				.toUriString();
			var s = c.state();
			return new CheckResponse(c.slug(), c.name(), c.pingKey(), pingUrl, c.scheduleKind(), c.cronExpression(),
					c.timeZone().getId(), c.periodSeconds(), c.graceSeconds(), c.maxRuntimeSeconds(), s.status(),
					s.downReason(), s.lastPingAt(), s.lastStartAt(), s.lastSuccessAt(), s.nextDueAt(),
					nextRunAt(c, now), c.createdAt());
		}

		/**
		 * When the schedule next expects a run, seen from {@code now}: {@code nextDueAt}
		 * while it is ahead; once it has passed (a late or missed run), the next cron
		 * time after now. A period schedule only moves on when a run arrives, so an
		 * overdue one has none. Null when paused or never pinged.
		 */
		static Instant nextRunAt(Check c, Instant now) {
			Instant due = c.state().nextDueAt();
			if (due == null || c.state().status() == CheckStatus.PAUSED) {
				return null;
			}
			if (!due.isBefore(now)) {
				return due;
			}
			return (c.scheduleKind() == ScheduleKind.CRON) ? Schedule.of(c).next(now) : null;
		}

	}

}
