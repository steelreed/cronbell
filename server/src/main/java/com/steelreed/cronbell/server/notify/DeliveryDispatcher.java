package com.steelreed.cronbell.server.notify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued deliveries (DESIGN.md §8). Due rows are claimed and leased in a short
 * transaction, then sent outside any transaction, in parallel on virtual threads. Failed
 * sends are retried with exponential backoff for {@link #RETRY_WINDOW} after the alert.
 */
@Component
public class DeliveryDispatcher {

	private static final Logger logger = LoggerFactory.getLogger(DeliveryDispatcher.class);

	/** An alert not delivered within this time after it was raised is given up. */
	public static final Duration RETRY_WINDOW = Duration.ofHours(1);

	/** How long a claimed row is held by its sender before others may take it. */
	public static final Duration LEASE = Duration.ofMinutes(2);

	static final Duration FIRST_RETRY = Duration.ofSeconds(30);

	static final Duration MAX_RETRY = Duration.ofMinutes(15);

	static final int BATCH_SIZE = 50;

	private final DeliveryRepository deliveries;

	private final ChannelRepository channels;

	private final Senders senders;

	private final TransactionTemplate transactions;

	private final Clock clock;

	public DeliveryDispatcher(DeliveryRepository deliveries, ChannelRepository channels, Senders senders,
			TransactionTemplate transactions, Clock clock) {
		this.deliveries = deliveries;
		this.channels = channels;
		this.senders = senders;
		this.transactions = transactions;
		this.clock = clock;
	}

	/** Sends every delivery that is due now. Returns how many were attempted. */
	public int runOnce() {
		int total = 0;
		while (true) {
			Instant now = now();
			List<DeliveryRepository.Delivery> batch = this.transactions
				.execute((status) -> this.deliveries.claimDue(now, now.plus(LEASE), BATCH_SIZE));
			if (batch == null || batch.isEmpty()) {
				return total;
			}
			try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
				for (DeliveryRepository.Delivery delivery : batch) {
					executor.execute(() -> deliver(delivery));
				}
			}
			total += batch.size();
			if (batch.size() < BATCH_SIZE) {
				return total;
			}
		}
	}

	private void deliver(DeliveryRepository.Delivery delivery) {
		try {
			Optional<Channel> channel = this.channels.findById(delivery.channelId());
			if (channel.isEmpty()) {
				return; // Deleted meanwhile; its rows are gone too.
			}
			Instant deadline = delivery.createdAt().plus(RETRY_WINDOW);
			if (now().isAfter(deadline)) {
				this.deliveries.markFailed(delivery.id(), giveUp(delivery.lastError(), delivery.attempts() - 1));
				return;
			}
			try {
				this.senders.send(channel.get(), delivery.alert(), delivery.id());
				this.deliveries.markDelivered(delivery.id(), now());
			}
			catch (DeliveryException ex) {
				failed(delivery, channel.get(), ex, deadline);
			}
		}
		catch (RuntimeException ex) {
			// The lease expires and the row is tried again.
			logger.error("Delivery {} could not be processed", delivery.id(), ex);
		}
	}

	private void failed(DeliveryRepository.Delivery delivery, Channel channel, DeliveryException ex, Instant deadline) {
		String error = ex.getMessage();
		if (!ex.retryable()) {
			logger.warn("Delivery {} to {} failed: {}", delivery.id(), channel, error);
			this.deliveries.markFailed(delivery.id(), error);
			return;
		}
		Duration wait = backoff(delivery.attempts());
		if (ex.retryAfter() != null && ex.retryAfter().compareTo(wait) > 0) {
			wait = ex.retryAfter();
		}
		Instant next = now().plus(wait);
		if (next.isAfter(deadline)) {
			logger.warn("Delivery {} to {} failed, giving up after {} attempts: {}", delivery.id(), channel,
					delivery.attempts(), error);
			this.deliveries.markFailed(delivery.id(), giveUp(error, delivery.attempts()));
			return;
		}
		logger.info("Delivery {} to {} failed (attempt {}), retrying at {}: {}", delivery.id(), channel,
				delivery.attempts(), next, error);
		this.deliveries.markRetry(delivery.id(), next, error);
	}

	/** 30 s after the first attempt, then doubling, at most 15 min. */
	static Duration backoff(int attempts) {
		int doublings = Math.min(Math.max(attempts - 1, 0), 10);
		Duration wait = FIRST_RETRY.multipliedBy(1L << doublings);
		return (wait.compareTo(MAX_RETRY) > 0) ? MAX_RETRY : wait;
	}

	private static String giveUp(String error, int attempts) {
		return "gave up after " + attempts + " attempts in " + RETRY_WINDOW.toMinutes() + " min"
				+ ((error != null) ? ": " + error : "");
	}

	private Instant now() {
		return this.clock.instant().truncatedTo(ChronoUnit.MILLIS);
	}

}
