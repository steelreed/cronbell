package com.steelreed.cronbell.server.alerts.web;

import java.time.Instant;
import java.util.List;

import com.steelreed.cronbell.server.accounts.ProjectPrincipal;
import com.steelreed.cronbell.server.alerts.internal.DeliveryRepository;
import com.steelreed.cronbell.server.alerts.internal.DeliveryRepository.CheckDelivery;
import com.steelreed.cronbell.server.checks.Check;
import com.steelreed.cronbell.server.checks.CheckService;
import com.steelreed.cronbell.server.shared.web.ManagementApi;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The alerts sent for a check (DESIGN.md §6): one delivery per channel and status event,
 * so its history can show where each change went.
 */
@RestController
@ManagementApi
class CheckAlertsController {

	static final int DEFAULT_LIMIT = 50;

	static final int MAX_LIMIT = 200;

	private final CheckService checks;

	private final DeliveryRepository deliveries;

	CheckAlertsController(CheckService checks, DeliveryRepository deliveries) {
		this.checks = checks;
		this.deliveries = deliveries;
	}

	/** Latest deliveries of the check, newest first. */
	@GetMapping("/api/v1/checks/{slug}/alerts")
	List<AlertResponse> alerts(@AuthenticationPrincipal ProjectPrincipal project, @PathVariable String slug,
			@RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new IllegalArgumentException("limit must be 1 to " + MAX_LIMIT);
		}
		Check check = this.checks.get(project.projectId(), slug);
		return this.deliveries.findRecentByCheck(check.id(), limit).stream().map(AlertResponse::of).toList();
	}

	/**
	 * @param eventId the status event that raised it; null for deliveries made before
	 * events were recorded
	 * @param status {@code PENDING} while it is being sent or retried
	 */
	record AlertResponse(String id, String eventId, String channelId, String channelName, String channelType,
			DeliveryRepository.Status status, String lastError, Instant createdAt, Instant deliveredAt) {

		static AlertResponse of(CheckDelivery d) {
			return new AlertResponse(d.id(), d.eventId(), d.channelId(), d.channelName(), d.channelType().id(),
					d.status(), d.lastError(), d.createdAt(), d.deliveredAt());
		}

	}

}
