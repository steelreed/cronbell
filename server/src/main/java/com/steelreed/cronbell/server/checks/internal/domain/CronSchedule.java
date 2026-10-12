package com.steelreed.cronbell.server.checks.internal.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.Objects;

import org.springframework.scheduling.support.CronExpression;

/**
 * A cron expression evaluated on the wall clock of a time zone.
 *
 * <p>
 * Accepts standard 5-field Unix cron ({@code min hour day month weekday}), 6-field cron
 * with a leading seconds field (Spring syntax), and macros such as {@code @daily}. A
 * 5-field expression is run at second 0.
 *
 * <p>
 * Daylight-saving time: a run that falls in a skipped hour is due at the first valid
 * instant after the gap (the transition itself). A run in a repeated hour is due at its
 * first occurrence.
 */
public final class CronSchedule implements Schedule {

	private final String expression;

	private final ZoneId zone;

	private final CronExpression cron;

	private CronSchedule(String expression, ZoneId zone, CronExpression cron) {
		this.expression = expression;
		this.zone = zone;
		this.cron = cron;
	}

	/**
	 * Parses a 5-field, 6-field or macro expression.
	 * @throws IllegalArgumentException if the expression is not valid
	 */
	public static CronSchedule parse(String expression, ZoneId zone) {
		Objects.requireNonNull(expression, "expression");
		Objects.requireNonNull(zone, "zone");
		String trimmed = expression.trim().replaceAll("\\s+", " ");
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException("cron expression is empty");
		}
		int fields = trimmed.split(" ").length;
		String springExpression = switch (fields) {
			case 1 -> requireMacro(trimmed);
			case 5 -> "0 " + trimmed;
			case 6 -> trimmed;
			default -> throw new IllegalArgumentException(
					"cron expression must have 5 or 6 fields, found " + fields + ": " + trimmed);
		};
		return new CronSchedule(trimmed, zone, CronExpression.parse(springExpression));
	}

	private static String requireMacro(String expression) {
		if (!expression.startsWith("@")) {
			throw new IllegalArgumentException("cron expression must have 5 or 6 fields: " + expression);
		}
		return expression;
	}

	public String expression() {
		return this.expression;
	}

	public ZoneId zone() {
		return this.zone;
	}

	@Override
	public Instant next(Instant after) {
		ZoneRules rules = this.zone.getRules();
		LocalDateTime cursor = LocalDateTime.ofInstant(after, this.zone);
		// Bounded: a few iterations at most around an overlap; the bound only guards bad
		// input.
		for (int i = 0; i < 1000; i++) {
			LocalDateTime local = this.cron.next(cursor);
			if (local == null) {
				throw new IllegalStateException("cron expression never fires again: " + this.expression);
			}
			Instant candidate = resolve(rules, local, after);
			if (candidate != null) {
				return candidate;
			}
			cursor = local;
		}
		throw new IllegalStateException("no next run found for " + this.expression);
	}

	/**
	 * Maps a wall-clock time to the first instant after {@code after}, or null if none.
	 */
	private static Instant resolve(ZoneRules rules, LocalDateTime local, Instant after) {
		List<ZoneOffset> offsets = rules.getValidOffsets(local);
		if (offsets.isEmpty()) {
			// In a gap: due at the first valid instant after it.
			ZoneOffsetTransition gap = rules.getTransition(local);
			Instant instant = gap.getInstant();
			return instant.isAfter(after) ? instant : null;
		}
		// One offset normally; two in an overlap, earlier offset first.
		for (ZoneOffset offset : offsets) {
			Instant instant = local.toInstant(offset);
			if (instant.isAfter(after)) {
				return instant;
			}
		}
		return null;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof CronSchedule that && this.expression.equals(that.expression)
				&& this.zone.equals(that.zone);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.expression, this.zone);
	}

	@Override
	public String toString() {
		return "CronSchedule[" + this.expression + " " + this.zone + "]";
	}

}
