package com.steelreed.cronbell.server.checks.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;

import com.steelreed.cronbell.server.checks.StatusEvent;

/**
 * How a check spent its time, from its status events (DESIGN.md §6, Status history): the
 * uptime of the check page and the days of the overview. Pure.
 */
public final class StatusTimeline {

	private StatusTimeline() {
	}

	/** What a day looked like, worst first. */
	public enum Day {

		/** DOWN at the end of the day (or now, for today). */
		DOWN,

		/** DOWN for a while, or a failed run, but UP again by the end of the day. */
		ISSUES,

		/** Runs, none failed, never DOWN. */
		OK,

		/**
		 * No run and never DOWN: quiet, paused, never pinged, or before the check
		 * existed.
		 */
		NONE

	}

	/** Time spent up, DOWN and paused in a span, and whether it ended DOWN. */
	public record Span(Duration up, Duration down, Duration paused, boolean downAtEnd) {

		static final Span EMPTY = new Span(Duration.ZERO, Duration.ZERO, Duration.ZERO, false);

		/**
		 * The share of the time not DOWN, paused time left out; empty if none is left.
		 */
		public OptionalDouble uptime() {
			long counted = this.up.toMillis() + this.down.toMillis();
			return (counted > 0) ? OptionalDouble.of((double) this.up.toMillis() / counted) : OptionalDouble.empty();
		}

		/** The day this span was, given the runs and failed runs it had. */
		public Day day(long runs, long failures) {
			if (this.downAtEnd) {
				return Day.DOWN;
			}
			if (this.down.isPositive() || failures > 0) {
				return Day.ISSUES;
			}
			return (runs > 0) ? Day.OK : Day.NONE;
		}

	}

	private enum State {

		UP, DOWN, PAUSED;

		static State after(StatusEvent event) {
			return switch (event.kind()) {
				case UP, RESUMED -> UP;
				case DOWN -> DOWN;
				case PAUSED -> PAUSED;
			};
		}

	}

	/**
	 * How a check spent the time from {@code from} up to {@code to}. The latest event at
	 * or before {@code from} gives the status at {@code from}; with none the check counts
	 * as up (it was NEW, or nothing older is kept).
	 * @param oldestFirst status events of one check, oldest first
	 */
	public static Span between(List<StatusEvent> oldestFirst, Instant from, Instant to) {
		if (!from.isBefore(to)) {
			return Span.EMPTY;
		}
		long[] millis = new long[State.values().length];
		State state = State.UP;
		Instant cursor = from;
		for (StatusEvent event : oldestFirst) {
			if (!event.at().isAfter(from)) {
				state = State.after(event);
				continue;
			}
			if (!event.at().isBefore(to)) {
				break;
			}
			millis[state.ordinal()] += Duration.between(cursor, event.at()).toMillis();
			state = State.after(event);
			cursor = event.at();
		}
		millis[state.ordinal()] += Duration.between(cursor, to).toMillis();
		return new Span(Duration.ofMillis(millis[State.UP.ordinal()]), Duration.ofMillis(millis[State.DOWN.ordinal()]),
				Duration.ofMillis(millis[State.PAUSED.ordinal()]), state == State.DOWN);
	}

}
