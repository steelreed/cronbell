package com.steelreed.cronbell.server.check;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.steelreed.cronbell.server.db.Ids;
import com.steelreed.cronbell.server.schedule.CronSchedule;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates, updates and pauses checks of a project. */
@Service
public class CheckService {

	/** Default grace time, as in Healthchecks.io. */
	public static final long DEFAULT_GRACE_SECONDS = 3600;

	public static final long MIN_PERIOD_SECONDS = 60;

	private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

	/**
	 * A check definition as sent by the client. Exactly one of {@code cron} and
	 * {@code periodSeconds}.
	 */
	public record Definition(String name, String cron, String timeZone, Long periodSeconds, Long graceSeconds,
			Long maxRuntimeSeconds) {
	}

	public record Saved(Check check, boolean created) {
	}

	private final CheckRepository checks;

	private final Clock clock;

	public CheckService(CheckRepository checks, Clock clock) {
		this.checks = checks;
		this.clock = clock;
	}

	public List<Check> list(String projectId) {
		return this.checks.findAllByProject(projectId);
	}

	/**
	 * Creates the check or updates the one with this slug, so registration is idempotent.
	 * The ping key never changes. A changed schedule recomputes the next due time from
	 * now.
	 * @throws IllegalArgumentException if the definition is invalid
	 */
	@Transactional
	public Saved upsert(String projectId, String slug, Definition definition) {
		if (!SLUG.matcher(slug).matches()) {
			throw new IllegalArgumentException(
					"slug must be 1-128 characters of letters, digits, '.', '_' or '-', starting with a letter or digit");
		}
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MILLIS);
		Check defined = validate(projectId, slug, definition, now);
		var existing = this.checks.findBySlug(projectId, slug);
		if (existing.isEmpty()) {
			this.checks.insert(defined, now);
			return new Saved(defined, true);
		}
		Check current = existing.get();
		CheckState state = current.state();
		boolean scheduleChanged = defined.scheduleKind() != current.scheduleKind()
				|| !Objects.equals(defined.cronExpression(), current.cronExpression())
				|| !defined.timeZone().equals(current.timeZone())
				|| !Objects.equals(defined.periodSeconds(), current.periodSeconds());
		if (scheduleChanged && state.nextDueAt() != null) {
			Instant due = defined.schedule().next(now);
			state = new CheckState(state.status(), state.downReason(), state.lastPingAt(), state.lastStartAt(),
					state.lastSuccessAt(), due, state.runStartedAt(), state.runRid());
		}
		Check updated = new Check(current.id(), current.pingKey(), projectId, slug, defined.name(),
				defined.scheduleKind(), defined.cronExpression(), defined.timeZone(), defined.periodSeconds(),
				defined.graceSeconds(), defined.maxRuntimeSeconds(), state, current.createdAt());
		this.checks.update(updated, now);
		return new Saved(updated, false);
	}

	/** Pauses a check: no alerts until its next ping, which returns it to UP. */
	@Transactional
	public Check pause(String projectId, String slug) {
		Check check = this.checks.findBySlug(projectId, slug).orElseThrow(() -> new CheckNotFoundException(slug));
		CheckState s = check.state();
		CheckState paused = new CheckState(CheckStatus.PAUSED, null, s.lastPingAt(), s.lastStartAt(), s.lastSuccessAt(),
				s.nextDueAt(), s.runStartedAt(), s.runRid());
		this.checks.updateState(check.id(), paused, this.clock.instant().truncatedTo(ChronoUnit.MILLIS));
		return check.withState(paused);
	}

	private static Check validate(String projectId, String slug, Definition d, Instant now) {
		boolean hasCron = d.cron() != null && !d.cron().isBlank();
		boolean hasPeriod = d.periodSeconds() != null;
		if (hasCron == hasPeriod) {
			throw new IllegalArgumentException("exactly one of 'cron' and 'periodSeconds' is required");
		}
		ZoneId zone = parseZone(d.timeZone());
		ScheduleKind kind;
		String cron = null;
		if (hasCron) {
			kind = ScheduleKind.CRON;
			cron = CronSchedule.parse(d.cron(), zone).expression();
		}
		else {
			kind = ScheduleKind.PERIOD;
			if (d.periodSeconds() < MIN_PERIOD_SECONDS) {
				throw new IllegalArgumentException("periodSeconds must be at least " + MIN_PERIOD_SECONDS);
			}
		}
		long grace = (d.graceSeconds() != null) ? d.graceSeconds() : DEFAULT_GRACE_SECONDS;
		if (grace < 0) {
			throw new IllegalArgumentException("graceSeconds must not be negative");
		}
		if (d.maxRuntimeSeconds() != null && d.maxRuntimeSeconds() <= 0) {
			throw new IllegalArgumentException("maxRuntimeSeconds must be positive");
		}
		String name = (d.name() != null && !d.name().isBlank()) ? d.name().trim() : slug;
		if (name.length() > 200) {
			throw new IllegalArgumentException("name must be at most 200 characters");
		}
		return new Check(Ids.newId(), UUID.randomUUID(), projectId, slug, name, kind, cron, zone, d.periodSeconds(),
				grace, d.maxRuntimeSeconds(), CheckState.initial(), now);
	}

	private static ZoneId parseZone(String timeZone) {
		if (timeZone == null || timeZone.isBlank()) {
			return ZoneId.of("UTC");
		}
		try {
			return ZoneId.of(timeZone.trim());
		}
		catch (DateTimeException ex) {
			throw new IllegalArgumentException("unknown time zone: " + timeZone);
		}
	}

}
