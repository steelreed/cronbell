package com.steelreed.cronbell.server.checks.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import com.steelreed.cronbell.server.checks.Check;
import com.steelreed.cronbell.server.checks.CheckState;
import com.steelreed.cronbell.server.checks.internal.domain.CheckStateMachine;
import com.steelreed.cronbell.server.checks.internal.domain.Schedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Marks UP checks DOWN when a run is missed or runs too long (DESIGN.md §4). Runs every
 * 15 s by default ({@code cronbell.detector.interval}). Each run leaves a heartbeat; the
 * first run after a start gives a new deadline to the checks that could have reported
 * while the server was away, so downtime does not turn into alerts.
 *
 * <p>
 * Each batch is claimed and updated in one transaction. On PostgreSQL the claim uses
 * {@code FOR UPDATE SKIP LOCKED}, so several instances can run the detector at once
 * without double-processing a check.
 */
@Component
public class Detector {

	private static final Logger logger = LoggerFactory.getLogger(Detector.class);

	static final int BATCH_SIZE = 100;

	/**
	 * A gap between heartbeats longer than this, a few detector runs, means the server
	 * was away. Shorter restarts are ignored.
	 */
	static final Duration DOWNTIME_THRESHOLD = Duration.ofMinutes(2);

	private final CheckRepository checks;

	private final DetectorHeartbeat heartbeat;

	private final ApplicationEventPublisher events;

	private final TransactionTemplate transactions;

	private final Clock clock;

	private boolean downtimeHandled;

	public Detector(CheckRepository checks, DetectorHeartbeat heartbeat, ApplicationEventPublisher events,
			TransactionTemplate transactions, Clock clock) {
		this.checks = checks;
		this.heartbeat = heartbeat;
		this.events = events;
		this.transactions = transactions;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${cronbell.detector.interval:PT15S}",
			initialDelayString = "${cronbell.detector.interval:PT15S}")
	void scheduledRun() {
		try {
			if (!this.downtimeHandled) {
				resumeAfterDowntime();
				this.downtimeHandled = true;
			}
			runOnce();
		}
		catch (RuntimeException ex) {
			logger.error("Detector run failed", ex);
		}
	}

	/** Processes every overdue check. Returns how many went DOWN. */
	public int runOnce() {
		Instant now = now();
		int total = inBatches((claimed) -> detect(claimed, now), () -> this.checks.claimOverdue(now, BATCH_SIZE));
		this.heartbeat.beat(now);
		return total;
	}

	/**
	 * Run once at start, before the first detection. If the last heartbeat is older than
	 * {@link #DOWNTIME_THRESHOLD}, every check that could have reported in the gap gets a
	 * new deadline (DESIGN.md §4, Downtime). Returns how many checks did.
	 */
	public int resumeAfterDowntime() {
		Instant now = now();
		Optional<Instant> last = this.heartbeat.last();
		if (last.isEmpty() || Duration.between(last.get(), now).compareTo(DOWNTIME_THRESHOLD) <= 0) {
			return 0;
		}
		Instant from = last.get();
		int total = inBatches((claimed) -> resume(claimed, from, now),
				() -> this.checks.claimAffectedByDowntime(from, now, BATCH_SIZE));
		this.heartbeat.beat(now);
		logger.info("Server was away from {} to {}; {} checks given a new deadline", from, now, total);
		return total;
	}

	/**
	 * Claims and processes batches, one transaction each, until a batch changes fewer
	 * than a full batch. Changed checks no longer match the claim, so each batch finds
	 * new ones.
	 */
	private int inBatches(Function<List<Check>, Integer> process, Supplier<List<Check>> claim) {
		int total = 0;
		while (true) {
			Integer changed = this.transactions.execute((status) -> process.apply(claim.get()));
			total += changed;
			if (changed < BATCH_SIZE) {
				return total;
			}
		}
	}

	private int detect(List<Check> claimed, Instant now) {
		int changed = 0;
		for (Check check : claimed) {
			CheckStateMachine.Outcome outcome = CheckStateMachine.detect(check.state(), check.grace(),
					check.maxRuntime(), now);
			if (outcome.change() == CheckStateMachine.Change.NONE) {
				continue;
			}
			this.checks.updateState(check.id(), outcome.state(), now);
			this.events.publishEvent(outcome.toEvent(check, now));
			changed++;
		}
		return changed;
	}

	private int resume(List<Check> claimed, Instant from, Instant now) {
		int changed = 0;
		for (Check check : claimed) {
			CheckState resumed = CheckStateMachine.afterDowntime(check.state(), Schedule.of(check), check.grace(),
					check.maxRuntime(), from, now);
			if (!resumed.equals(check.state())) {
				this.checks.updateState(check.id(), resumed, now);
				changed++;
			}
		}
		return changed;
	}

	private Instant now() {
		return this.clock.instant().truncatedTo(ChronoUnit.MILLIS);
	}

}
