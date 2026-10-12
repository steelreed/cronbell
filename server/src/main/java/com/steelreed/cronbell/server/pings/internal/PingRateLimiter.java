package com.steelreed.cronbell.server.pings.internal;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Token bucket per check: up to {@code rate} pings per second, bursts of {@code rate}.
 *
 * <p>
 * In memory, so the limit is per server instance: with N instances behind a load balancer
 * a check can get up to N times the rate.
 */
@Component
public class PingRateLimiter {

	private final Clock clock;

	private final double rate;

	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	public PingRateLimiter(Clock clock, @Value("${cronbell.ping.rate-limit-per-second:5}") double rate) {
		this.clock = clock;
		this.rate = rate;
	}

	/** Takes one token for {@code checkId}; false when the check is over its rate. */
	public boolean tryAcquire(String checkId) {
		long now = this.clock.millis();
		return this.buckets.computeIfAbsent(checkId, id -> new Bucket(this.rate, now)).tryTake(this.rate, now);
	}

	/**
	 * Drops buckets unused for a minute (they are full again by then), so memory stays
	 * bounded.
	 */
	@Scheduled(fixedDelay = 60_000)
	void evictIdle() {
		long now = this.clock.millis();
		this.buckets.values().removeIf(bucket -> bucket.idleFor(now) > 60_000);
	}

	private static final class Bucket {

		private double tokens;

		private long refilledAt;

		private long usedAt;

		Bucket(double capacity, long now) {
			this.tokens = capacity;
			this.refilledAt = now;
			this.usedAt = now;
		}

		synchronized boolean tryTake(double rate, long now) {
			this.tokens = Math.min(rate, this.tokens + Math.max(0, now - this.refilledAt) * rate / 1000.0);
			this.refilledAt = Math.max(this.refilledAt, now);
			this.usedAt = now;
			if (this.tokens >= 1) {
				this.tokens -= 1;
				return true;
			}
			return false;
		}

		synchronized long idleFor(long now) {
			return now - this.usedAt;
		}

	}

}
