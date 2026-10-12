package com.steelreed.cronbell.server.history.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.IntSupplier;

import com.steelreed.cronbell.server.alerts.DeliveryHistory;
import com.steelreed.cronbell.server.pings.PingHistory;
import com.steelreed.cronbell.server.shared.database.Ids;
import com.steelreed.cronbell.server.shared.database.JobLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes pings and finished deliveries older than the retention (DESIGN.md §9, History).
 * Runs once a day and a minute after start. Rows go in batches, one short transaction
 * each. With several instances, the one holding the {@code history-pruning} lease does
 * the work and the others skip the run.
 */
@Component
public class HistoryPruner {

	private static final Logger logger = LoggerFactory.getLogger(HistoryPruner.class);

	static final String LOCK = "history-pruning";

	/** How long a batch may take before another instance may take over. */
	static final Duration LEASE = Duration.ofMinutes(10);

	/** What a run did. {@code ran} is false when another instance held the lease. */
	public record Result(boolean ran, int pings, int deliveries) {
	}

	private final PingHistory pings;

	private final DeliveryHistory deliveries;

	private final JobLocks locks;

	private final HistoryProperties properties;

	private final Clock clock;

	public HistoryPruner(PingHistory pings, DeliveryHistory deliveries, JobLocks locks, HistoryProperties properties,
			Clock clock) {
		this.pings = pings;
		this.deliveries = deliveries;
		this.locks = locks;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "P1D", initialDelayString = "PT1M")
	void scheduledRun() {
		try {
			runOnce();
		}
		catch (RuntimeException ex) {
			logger.error("History pruning failed", ex);
		}
	}

	public Result runOnce() {
		Instant now = now();
		String owner = Ids.newId();
		if (!this.locks.tryAcquire(LOCK, owner, now, now.plus(LEASE))) {
			logger.info("History pruning skipped: another instance is running it");
			return new Result(false, 0, 0);
		}
		try {
			Instant cutoff = now.minus(this.properties.retention());
			int batchSize = this.properties.batchSize();
			int deletedPings = deleteInBatches(owner, () -> this.pings.deleteBefore(cutoff, batchSize));
			int deletedDeliveries = deleteInBatches(owner,
					() -> this.deliveries.deleteFinishedBefore(cutoff, batchSize));
			logger.info("History pruned: deleted {} pings and {} deliveries older than {} (before {})", deletedPings,
					deletedDeliveries, describe(this.properties.retention()), cutoff);
			return new Result(true, deletedPings, deletedDeliveries);
		}
		finally {
			this.locks.release(LOCK, owner);
		}
	}

	private int deleteInBatches(String owner, IntSupplier batch) {
		int total = 0;
		while (true) {
			int deleted = batch.getAsInt();
			total += deleted;
			if (deleted < this.properties.batchSize()) {
				return total;
			}
			if (!this.locks.renew(LOCK, owner, now().plus(LEASE))) {
				throw new IllegalStateException("Lost the history-pruning lease after deleting " + total + " rows");
			}
		}
	}

	private static String describe(Duration retention) {
		return (retention.toSeconds() % 86400 == 0) ? retention.toDays() + "d" : retention.toString();
	}

	private Instant now() {
		return this.clock.instant().truncatedTo(ChronoUnit.MILLIS);
	}

}
