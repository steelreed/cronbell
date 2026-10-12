package com.steelreed.cronbell.server.alerts;

import java.time.Instant;

import com.steelreed.cronbell.server.alerts.internal.DeliveryRepository;

import org.springframework.stereotype.Component;

/** The stored alert deliveries, as history pruning deletes them. */
@Component
public class DeliveryHistory {

	private final DeliveryRepository deliveries;

	DeliveryHistory(DeliveryRepository deliveries) {
		this.deliveries = deliveries;
	}

	/**
	 * Deletes up to {@code limit} delivered or failed deliveries created before
	 * {@code cutoff}. Pending ones are kept, whatever their age. Returns how many were
	 * deleted.
	 */
	public int deleteFinishedBefore(Instant cutoff, int limit) {
		return this.deliveries.deleteFinishedBefore(cutoff, limit);
	}

}
