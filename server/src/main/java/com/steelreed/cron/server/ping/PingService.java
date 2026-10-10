package com.steelreed.cron.server.ping;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import com.steelreed.cron.server.check.Check;
import com.steelreed.cron.server.check.CheckEvent;
import com.steelreed.cron.server.check.CheckRepository;
import com.steelreed.cron.server.check.CheckStateMachine;
import com.steelreed.cron.server.check.PingKind;
import com.steelreed.cron.server.db.Ids;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records a ping and applies it to its check: one insert plus one update. */
@Service
public class PingService {

	/** Bodies are stored up to this many bytes and truncated beyond. */
	public static final int MAX_BODY_BYTES = 10 * 1024;

	public enum Result {

		OK, NOT_FOUND, RATE_LIMITED

	}

	/** What the job sent, before it is matched to a check. */
	public record Request(UUID pingKey, PingKind kind, Integer exitCode, String rid, String body, String remoteAddr,
			String userAgent) {
	}

	private final CheckRepository checks;

	private final PingRepository pings;

	private final PingRateLimiter rateLimiter;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	public PingService(CheckRepository checks, PingRepository pings, PingRateLimiter rateLimiter,
			ApplicationEventPublisher events, Clock clock) {
		this.checks = checks;
		this.pings = pings;
		this.rateLimiter = rateLimiter;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public Result ping(Request request) {
		Optional<Check> found = this.checks.findByPingKeyForUpdate(request.pingKey());
		if (found.isEmpty()) {
			return Result.NOT_FOUND;
		}
		Check check = found.get();
		if (!this.rateLimiter.tryAcquire(check.id())) {
			return Result.RATE_LIMITED;
		}
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MILLIS);
		this.pings.insert(new Ping(Ids.newId(), check.id(), request.kind(), request.exitCode(), request.rid(),
				request.body(), now, request.remoteAddr(), request.userAgent()));
		CheckStateMachine.Outcome outcome = CheckStateMachine.onPing(check.state(), check.schedule(), request.kind(),
				request.rid(), now);
		if (!outcome.state().equals(check.state())) {
			this.checks.updateState(check.id(), outcome.state(), now);
		}
		if (outcome.change() != CheckStateMachine.Change.NONE) {
			this.events.publishEvent(CheckEvent.of(check, outcome.change(), outcome.state(), now));
		}
		return Result.OK;
	}

}
