package com.steelreed.cronbell.server.pings.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import com.steelreed.cronbell.server.checks.Check;
import com.steelreed.cronbell.server.checks.CheckService;
import com.steelreed.cronbell.server.checks.PingKind;
import com.steelreed.cronbell.server.pings.Ping;
import com.steelreed.cronbell.server.shared.database.Ids;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records a ping and applies it to its check: one insert plus one update. */
@Service
public class PingService {

	public enum Result {

		OK, NOT_FOUND, RATE_LIMITED

	}

	/** What the job sent, before it is matched to a check. */
	public record Request(UUID pingKey, PingKind kind, Integer exitCode, String rid, String body, String remoteAddr,
			String userAgent) {
	}

	private final CheckService checks;

	private final PingRepository pings;

	private final PingRateLimiter rateLimiter;

	private final Clock clock;

	public PingService(CheckService checks, PingRepository pings, PingRateLimiter rateLimiter, Clock clock) {
		this.checks = checks;
		this.pings = pings;
		this.rateLimiter = rateLimiter;
		this.clock = clock;
	}

	@Transactional
	public Result ping(Request request) {
		Optional<Check> found = this.checks.lockByPingKey(request.pingKey());
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
		this.checks.applyPing(check, request.kind(), request.rid(), now);
		return Result.OK;
	}

}
