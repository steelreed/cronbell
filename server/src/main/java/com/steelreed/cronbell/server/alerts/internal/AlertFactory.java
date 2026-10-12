package com.steelreed.cronbell.server.alerts.internal;

import java.util.List;

import com.steelreed.cronbell.server.checks.Check;
import com.steelreed.cronbell.server.checks.CheckEvent;
import com.steelreed.cronbell.server.checks.CheckService;
import com.steelreed.cronbell.server.checks.CheckState;
import com.steelreed.cronbell.server.checks.CheckStatus;
import com.steelreed.cronbell.server.checks.DownReason;
import com.steelreed.cronbell.server.checks.PingKind;
import com.steelreed.cronbell.server.pings.Ping;
import com.steelreed.cronbell.server.pings.PingHistory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link Alert} for a status change from the check and its latest pings
 * (DESIGN.md §8). Runs inside the transaction that changed the check.
 */
@Component
class AlertFactory {

	/** Characters of the latest ping body shown in an alert (its end). */
	static final int LOG_EXCERPT_CHARS = 1000;

	private static final int RECENT_PINGS = 20;

	private final CheckService checks;

	private final PingHistory pings;

	private final String baseUrl;

	AlertFactory(CheckService checks, PingHistory pings, @Value("${cronbell.base-url:}") String baseUrl) {
		this.checks = checks;
		this.pings = pings;
		this.baseUrl = baseUrl.strip().replaceAll("/+$", "");
	}

	Alert create(CheckEvent event) {
		Check check = this.checks.findById(event.checkId())
			.orElseThrow(() -> new IllegalStateException("check " + event.checkId() + " not found"));
		CheckState s = check.state();
		String url = this.baseUrl.isEmpty() ? null : this.baseUrl + "/checks/" + check.slug();
		String zone = check.timeZone().getId();
		return switch (event) {
			case CheckEvent.WentDown down -> {
				DownReason reason = down.reason();
				List<Ping> recent = this.pings.recent(check.id(), RECENT_PINGS);
				Ping logPing = recent.stream()
					.filter((ping) -> ping.body() != null && !ping.body().isBlank())
					.findFirst()
					.orElse(null);
				Integer exitCode = (reason == DownReason.FAILED) ? recent.stream()
					.filter((ping) -> ping.kind() == PingKind.FAIL)
					.findFirst()
					.map(Ping::exitCode)
					.orElse(null) : null;
				boolean missed = reason == DownReason.MISSED;
				boolean tooLong = reason == DownReason.RUNNING_TOO_LONG;
				yield new Alert(Alert.Kind.DOWN, down.at(), check.slug(), check.name(), CheckStatus.DOWN, reason, zone,
						missed ? s.nextDueAt() : null, missed ? check.graceSeconds() : null, exitCode,
						tooLong ? s.runStartedAt() : null, tooLong ? check.maxRuntime().toSeconds() : null,
						s.lastSuccessAt(), (logPing != null) ? excerpt(logPing.body()) : null,
						(logPing != null) ? logPing.receivedAt() : null, url);
			}
			case CheckEvent.Recovered up ->
				new Alert(Alert.Kind.UP, up.at(), check.slug(), check.name(), CheckStatus.UP, null, zone, s.nextDueAt(),
						null, null, null, null, s.lastSuccessAt(), null, null, url);
		};
	}

	static String excerpt(String body) {
		String trimmed = body.strip();
		if (trimmed.length() <= LOG_EXCERPT_CHARS) {
			return trimmed;
		}
		int start = trimmed.length() - LOG_EXCERPT_CHARS;
		if (Character.isLowSurrogate(trimmed.charAt(start))) {
			start++;
		}
		return "…" + trimmed.substring(start);
	}

}
