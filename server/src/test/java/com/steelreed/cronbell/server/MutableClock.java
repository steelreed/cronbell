package com.steelreed.cronbell.server;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can set and move. */
public final class MutableClock extends Clock {

	private volatile Instant now;

	public MutableClock(Instant now) {
		this.now = now;
	}

	public void set(Instant now) {
		this.now = now;
	}

	public void set(String isoInstant) {
		set(Instant.parse(isoInstant));
	}

	public Instant advance(Duration duration) {
		this.now = this.now.plus(duration);
		return this.now;
	}

	@Override
	public Instant instant() {
		return this.now;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException();
	}

}
