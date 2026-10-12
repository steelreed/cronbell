package com.steelreed.cronbell.server.accounts.internal.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Slows down password guessing (DESIGN.md §6): failures are counted per client address
 * and email. After {@value #FREE_FAILURES} failures in a row, attempts are refused for 30
 * s, doubling with each further failure up to 15 minutes. Kept in memory, so per server
 * instance.
 */
@Component
public class LoginRateLimiter {

	static final int FREE_FAILURES = 5;

	static final Duration FIRST_BLOCK = Duration.ofSeconds(30);

	static final Duration MAX_BLOCK = Duration.ofMinutes(15);

	/** Counts are forgotten this long after the last failure. */
	static final Duration FORGET_AFTER = Duration.ofHours(1);

	private static final int MAX_ENTRIES = 10_000;

	private record Entry(int failures, Instant lastFailure, Instant blockedUntil) {
	}

	private final Map<String, Entry> entries = new ConcurrentHashMap<>();

	private final Clock clock;

	public LoginRateLimiter(Clock clock) {
		this.clock = clock;
	}

	/** How long the caller must wait before trying again, if blocked. */
	public Optional<Duration> retryAfter(String clientAddress, String email) {
		Entry entry = this.entries.get(key(clientAddress, email));
		Instant now = this.clock.instant();
		if (entry == null || entry.blockedUntil() == null || !now.isBefore(entry.blockedUntil())) {
			return Optional.empty();
		}
		return Optional.of(Duration.between(now, entry.blockedUntil()));
	}

	public void failed(String clientAddress, String email) {
		Instant now = this.clock.instant();
		if (this.entries.size() > MAX_ENTRIES) {
			this.entries.values().removeIf((entry) -> isStale(entry, now));
		}
		this.entries.compute(key(clientAddress, email), (key, entry) -> {
			int failures = (entry == null || isStale(entry, now)) ? 1 : entry.failures() + 1;
			Instant blockedUntil = null;
			if (failures >= FREE_FAILURES) {
				Duration block = FIRST_BLOCK.multipliedBy(1L << Math.min(failures - FREE_FAILURES, 10));
				blockedUntil = now.plus((block.compareTo(MAX_BLOCK) > 0) ? MAX_BLOCK : block);
			}
			return new Entry(failures, now, blockedUntil);
		});
	}

	public void succeeded(String clientAddress, String email) {
		this.entries.remove(key(clientAddress, email));
	}

	private static boolean isStale(Entry entry, Instant now) {
		return entry.lastFailure().plus(FORGET_AFTER).isBefore(now)
				&& (entry.blockedUntil() == null || entry.blockedUntil().isBefore(now));
	}

	private static String key(String clientAddress, String email) {
		return clientAddress + "|" + ((email != null) ? email.strip().toLowerCase(java.util.Locale.ROOT) : "");
	}

}
