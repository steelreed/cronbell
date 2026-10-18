package com.steelreed.cronbell.server.checks;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.steelreed.cronbell.server.checks.internal.CheckChannelRepository;
import com.steelreed.cronbell.server.checks.internal.CheckRepository;
import com.steelreed.cronbell.server.checks.internal.ChecksProperties;
import com.steelreed.cronbell.server.checks.internal.StatusEventRepository;
import com.steelreed.cronbell.server.checks.internal.domain.CheckStateMachine;
import com.steelreed.cronbell.server.checks.internal.domain.CronSchedule;
import com.steelreed.cronbell.server.checks.internal.domain.Schedule;
import com.steelreed.cronbell.server.shared.database.Ids;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The checks module's API: creates, updates and pauses checks of a project, and applies
 * the pings recorded by the pings module to the state of their check.
 */
@Service
public class CheckService {

	/** Default grace time, as in Healthchecks.io. */
	public static final long DEFAULT_GRACE_SECONDS = 3600;

	public static final long MIN_PERIOD_SECONDS = 60;

	private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

	/**
	 * A check definition as sent by the client. Exactly one of {@code cron} and
	 * {@code periodSeconds}. {@code channels} are the alert channels the check picks:
	 * empty for every channel of the project, {@code null} to keep the current selection
	 * (every channel for a new check).
	 */
	public record Definition(String name, String cron, String timeZone, Long periodSeconds, Long graceSeconds,
			Long maxRuntimeSeconds, List<String> channels) {
	}

	public record Saved(Check check, boolean created) {
	}

	private final CheckRepository checks;

	private final CheckChannelRepository checkChannels;

	private final AlertChannels alertChannels;

	private final StatusEventRepository history;

	private final ApplicationEventPublisher events;

	private final ChecksProperties properties;

	private final Clock clock;

	public CheckService(CheckRepository checks, CheckChannelRepository checkChannels, AlertChannels alertChannels,
			StatusEventRepository history, ApplicationEventPublisher events, ChecksProperties properties, Clock clock) {
		this.checks = checks;
		this.checkChannels = checkChannels;
		this.alertChannels = alertChannels;
		this.history = history;
		this.events = events;
		this.properties = properties;
		this.clock = clock;
	}

	public List<Check> list(String projectId) {
		return this.checks.findAllByProject(projectId);
	}

	public Optional<Check> findById(String id) {
		return this.checks.findById(id);
	}

	/**
	 * The alert channels the check picked, or an empty list when it alerts every channel
	 * of its project (DESIGN.md §8, Routing).
	 */
	public List<String> channels(String checkId) {
		return this.checkChannels.findByCheck(checkId);
	}

	/**
	 * Check id to the channels it picked, for the checks of the project that picked some.
	 */
	public Map<String, List<String>> channelsByCheck(String projectId) {
		return this.checkChannels.findByProject(projectId);
	}

	/**
	 * Finds the check of a ping key and, on PostgreSQL, locks its row until the caller's
	 * transaction ends, so pings of one check are applied one after the other.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<Check> lockByPingKey(UUID pingKey) {
		return this.checks.findByPingKeyForUpdate(pingKey);
	}

	/**
	 * Applies a ping received at {@code at} to a check found with {@link #lockByPingKey}:
	 * stores the new state if it changed, records a {@link StatusEvent} if the status
	 * changed, and publishes a {@link CheckEvent} when the check went DOWN or came back
	 * UP.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void applyPing(Check check, PingKind kind, String rid, Instant at) {
		CheckStateMachine.Outcome outcome = CheckStateMachine.onPing(check.state(), Schedule.of(check), kind, rid, at);
		if (outcome.state().equals(check.state())) {
			return;
		}
		this.checks.updateState(check.id(), outcome.state(), at);
		Optional<StatusEvent> recorded = this.history.recordChange(check, outcome.state(), at);
		if (outcome.change() != CheckStateMachine.Change.NONE) {
			this.events.publishEvent(outcome.toEvent(check, at, recorded.orElseThrow().id()));
		}
	}

	/**
	 * Creates the check or updates the one with this slug, so registration is idempotent.
	 * The ping key never changes. A changed schedule recomputes the next due time from
	 * now. An existing check is locked first, as for a ping, so its state and its picked
	 * channels are written together and never over a concurrent ping, pause or resume.
	 * @throws IllegalArgumentException if the definition is invalid or picks a channel
	 * that is not one of the project's
	 * @throws CheckLimitReachedException if a new check would pass the project's limit
	 */
	@Transactional
	public Saved upsert(String projectId, String slug, Definition definition) {
		if (!SLUG.matcher(slug).matches()) {
			throw new IllegalArgumentException(
					"slug must be 1-128 characters of letters, digits, '.', '_' or '-', starting with a letter or digit");
		}
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MILLIS);
		Check defined = validate(projectId, slug, definition, now);
		List<String> channels = pickedChannels(projectId, definition.channels());
		var existing = this.checks.findBySlugForUpdate(projectId, slug);
		if (existing.isEmpty()) {
			Integer limit = this.properties.maxPerProject();
			if (limit != null && this.checks.countByProject(projectId) >= limit) {
				throw new CheckLimitReachedException(limit);
			}
			this.checks.insert(defined, now);
			if (channels != null) {
				this.checkChannels.replace(defined.id(), channels);
			}
			return new Saved(defined, true);
		}
		Check current = existing.get();
		CheckState state = current.state();
		boolean scheduleChanged = defined.scheduleKind() != current.scheduleKind()
				|| !Objects.equals(defined.cronExpression(), current.cronExpression())
				|| !defined.timeZone().equals(current.timeZone())
				|| !Objects.equals(defined.periodSeconds(), current.periodSeconds());
		if (scheduleChanged && state.nextDueAt() != null) {
			Instant due = Schedule.of(defined).next(now);
			state = new CheckState(state.status(), state.downReason(), state.lastPingAt(), state.lastStartAt(),
					state.lastSuccessAt(), due, state.runStartedAt(), state.runRid());
		}
		Check updated = new Check(current.id(), current.pingKey(), projectId, slug, defined.name(),
				defined.scheduleKind(), defined.cronExpression(), defined.timeZone(), defined.periodSeconds(),
				defined.graceSeconds(), defined.maxRuntimeSeconds(), state, current.createdAt());
		this.checks.update(updated, now);
		if (channels != null) {
			this.checkChannels.replace(current.id(), channels);
		}
		return new Saved(updated, false);
	}

	public Check get(String projectId, String slug) {
		return this.checks.findBySlug(projectId, slug).orElseThrow(() -> new CheckNotFoundException(slug));
	}

	/** Deletes a check with its pings and pending alerts. */
	@Transactional
	public void delete(String projectId, String slug) {
		if (!this.checks.delete(projectId, slug)) {
			throw new CheckNotFoundException(slug);
		}
	}

	/**
	 * Ends a pause without a ping (DESIGN.md §3): back to NEW if never pinged, else UP
	 * with no open run and the next due time computed from now. A check that is not
	 * paused is left alone. The check is locked first, as for a ping, so a concurrent
	 * pause, resume or ping is applied before or after, never on a stale state.
	 */
	@Transactional
	public Check resume(String projectId, String slug) {
		Check check = lockBySlug(projectId, slug);
		CheckState s = check.state();
		if (s.status() != CheckStatus.PAUSED) {
			return check;
		}
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MILLIS);
		CheckState resumed = (s.lastPingAt() == null)
				? new CheckState(CheckStatus.NEW, null, null, s.lastStartAt(), s.lastSuccessAt(), null, null, null)
				: new CheckState(CheckStatus.UP, null, s.lastPingAt(), s.lastStartAt(), s.lastSuccessAt(),
						Schedule.of(check).next(now), null, null);
		this.checks.updateState(check.id(), resumed, now);
		this.history.record(check, StatusEvent.Kind.RESUMED, resumed, now);
		return check.withState(resumed);
	}

	/**
	 * Pauses a check: no alerts until its next ping, which returns it to UP. Locks the
	 * check first, as {@link #resume} does.
	 */
	@Transactional
	public Check pause(String projectId, String slug) {
		Check check = lockBySlug(projectId, slug);
		CheckState s = check.state();
		CheckState paused = new CheckState(CheckStatus.PAUSED, null, s.lastPingAt(), s.lastStartAt(), s.lastSuccessAt(),
				s.nextDueAt(), s.runStartedAt(), s.runRid());
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MILLIS);
		this.checks.updateState(check.id(), paused, now);
		this.history.recordChange(check, paused, now);
		return check.withState(paused);
	}

	/**
	 * The picked channels without duplicates, after checking that each is a channel of
	 * the project. Another project's channel is refused like an unknown one.
	 */
	private List<String> pickedChannels(String projectId, List<String> picked) {
		if (picked == null || picked.isEmpty()) {
			return picked;
		}
		Set<String> known = this.alertChannels.idsOf(projectId);
		for (String id : picked) {
			if (id == null || !known.contains(id)) {
				throw new IllegalArgumentException("channels: no channel with id " + id + " in this project");
			}
		}
		return picked.stream().distinct().toList();
	}

	private Check lockBySlug(String projectId, String slug) {
		return this.checks.findBySlugForUpdate(projectId, slug).orElseThrow(() -> new CheckNotFoundException(slug));
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
