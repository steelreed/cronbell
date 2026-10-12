package com.steelreed.cronbell.server.pings;

import java.time.Instant;
import java.util.List;

import com.steelreed.cronbell.server.pings.internal.PingRepository;

import org.springframework.stereotype.Component;

/** The stored pings, as other modules read and prune them. */
@Component
public class PingHistory {

	private final PingRepository pings;

	PingHistory(PingRepository pings) {
		this.pings = pings;
	}

	/** The latest pings of a check, most recent first. */
	public List<Ping> recent(String checkId, int limit) {
		return this.pings.findRecent(checkId, limit);
	}

	/**
	 * Deletes up to {@code limit} pings received before {@code cutoff}, keeping what the
	 * state machine and alerts still read (DESIGN.md §9, History). Returns how many were
	 * deleted.
	 */
	public int deleteBefore(Instant cutoff, int limit) {
		return this.pings.deleteBefore(cutoff, limit);
	}

}
