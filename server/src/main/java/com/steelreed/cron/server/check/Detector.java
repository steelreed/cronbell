package com.steelreed.cron.server.check;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Marks UP checks DOWN when a run is missed or runs too long (DESIGN.md §4). Runs every 15 s by
 * default ({@code cron.detector.interval}).
 *
 * <p>Each batch is claimed and updated in one transaction. On PostgreSQL the claim uses
 * {@code FOR UPDATE SKIP LOCKED}, so several instances can run the detector at once without
 * double-processing a check.
 */
@Component
public class Detector {

	private static final Logger logger = LoggerFactory.getLogger(Detector.class);

	static final int BATCH_SIZE = 100;

	private final CheckRepository checks;

	private final ApplicationEventPublisher events;

	private final TransactionTemplate transactions;

	private final Clock clock;

	public Detector(CheckRepository checks, ApplicationEventPublisher events, TransactionTemplate transactions,
			Clock clock) {
		this.checks = checks;
		this.events = events;
		this.transactions = transactions;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${cron.detector.interval:PT15S}",
			initialDelayString = "${cron.detector.interval:PT15S}")
	void scheduledRun() {
		try {
			runOnce();
		}
		catch (RuntimeException ex) {
			logger.error("Detector run failed", ex);
		}
	}

	/** Processes every overdue check. Returns how many went DOWN. */
	public int runOnce() {
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MILLIS);
		int total = 0;
		while (true) {
			Integer changed = this.transactions.execute(status -> processBatch(now));
			total += changed;
			if (changed < BATCH_SIZE) {
				return total;
			}
		}
	}

	private int processBatch(Instant now) {
		List<Check> claimed = this.checks.claimOverdue(now, BATCH_SIZE);
		int changed = 0;
		for (Check check : claimed) {
			CheckStateMachine.Outcome outcome = CheckStateMachine.detect(check.state(), check.grace(),
					check.maxRuntime(), now);
			if (outcome.change() == CheckStateMachine.Change.NONE) {
				continue;
			}
			this.checks.updateState(check.id(), outcome.state(), now);
			this.events.publishEvent(CheckEvent.of(check, outcome.change(), outcome.state(), now));
			changed++;
		}
		return changed;
	}

}
