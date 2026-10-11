package com.steelreed.cronbell.server.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.steelreed.cronbell.server.check.Check;
import com.steelreed.cronbell.server.check.CheckService;
import com.steelreed.cronbell.server.check.CheckStatus;
import com.steelreed.cronbell.server.check.DownReason;
import com.steelreed.cronbell.server.check.ScheduleKind;
import com.steelreed.cronbell.server.security.ProjectPrincipal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@RequestMapping("/api/v1/checks")
class ChecksController {

	private final CheckService checks;

	ChecksController(CheckService checks) {
		this.checks = checks;
	}

	@GetMapping
	List<CheckResponse> list(@AuthenticationPrincipal ProjectPrincipal project) {
		return this.checks.list(project.projectId()).stream().map(CheckResponse::of).toList();
	}

	/**
	 * Creates or updates the check with this slug. 201 when created, 200 when updated.
	 */
	@PutMapping("/{slug}")
	ResponseEntity<CheckResponse> upsert(@AuthenticationPrincipal ProjectPrincipal project, @PathVariable String slug,
			@RequestBody CheckRequest request) {
		CheckService.Saved saved = this.checks.upsert(project.projectId(), slug, request.toDefinition());
		return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.body(CheckResponse.of(saved.check()));
	}

	@PostMapping("/{slug}/pause")
	CheckResponse pause(@AuthenticationPrincipal ProjectPrincipal project, @PathVariable String slug) {
		return CheckResponse.of(this.checks.pause(project.projectId(), slug));
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
			Instant createdAt) {

		static CheckResponse of(Check c) {
			String pingUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
				.path("/ping/{key}")
				.buildAndExpand(c.pingKey())
				.toUriString();
			var s = c.state();
			return new CheckResponse(c.slug(), c.name(), c.pingKey(), pingUrl, c.scheduleKind(), c.cronExpression(),
					c.timeZone().getId(), c.periodSeconds(), c.graceSeconds(), c.maxRuntimeSeconds(), s.status(),
					s.downReason(), s.lastPingAt(), s.lastStartAt(), s.lastSuccessAt(), s.nextDueAt(), c.createdAt());
		}

	}

}
