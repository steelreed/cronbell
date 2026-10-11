package com.steelreed.cronbell.server.ping;

import java.time.Duration;
import java.time.Instant;

import com.steelreed.cronbell.server.MutableClock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PingRateLimiterTests {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-01-05T10:00:00Z"));

	private final PingRateLimiter limiter = new PingRateLimiter(this.clock, 5);

	@Test
	void allowsABurstOfFiveThenLimits() {
		for (int i = 0; i < 5; i++) {
			assertThat(this.limiter.tryAcquire("a")).isTrue();
		}
		assertThat(this.limiter.tryAcquire("a")).isFalse();
	}

	@Test
	void refillsAtTheRate() {
		for (int i = 0; i < 5; i++) {
			this.limiter.tryAcquire("a");
		}
		this.clock.advance(Duration.ofMillis(200));
		assertThat(this.limiter.tryAcquire("a")).isTrue();
		assertThat(this.limiter.tryAcquire("a")).isFalse();
		this.clock.advance(Duration.ofSeconds(10));
		for (int i = 0; i < 5; i++) {
			assertThat(this.limiter.tryAcquire("a")).isTrue();
		}
		assertThat(this.limiter.tryAcquire("a")).isFalse();
	}

	@Test
	void checksAreLimitedSeparately() {
		for (int i = 0; i < 5; i++) {
			this.limiter.tryAcquire("a");
		}
		assertThat(this.limiter.tryAcquire("a")).isFalse();
		assertThat(this.limiter.tryAcquire("b")).isTrue();
	}

	@Test
	void evictsIdleBuckets() {
		for (int i = 0; i < 5; i++) {
			this.limiter.tryAcquire("a");
		}
		this.clock.advance(Duration.ofMinutes(2));
		this.limiter.evictIdle();
		for (int i = 0; i < 5; i++) {
			assertThat(this.limiter.tryAcquire("a")).isTrue();
		}
	}

}
