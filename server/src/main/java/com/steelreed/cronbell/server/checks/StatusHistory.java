package com.steelreed.cronbell.server.checks;

import java.time.Instant;

import com.steelreed.cronbell.server.checks.internal.StatusEventRepository;

import org.springframework.stereotype.Component;

/** The status events of checks, as history pruning deletes them. */
@Component
public class StatusHistory {

	private final StatusEventRepository events;

	StatusHistory(StatusEventRepository events) {
		this.events = events;
	}

	/**
	 * Deletes up to {@code limit} status events older than {@code cutoff}, keeping the
	 * latest one of each check before it (DESIGN.md §9, History). Returns how many were
	 * deleted.
	 */
	public int deleteBefore(Instant cutoff, int limit) {
		return this.events.deleteBefore(cutoff, limit);
	}

}
