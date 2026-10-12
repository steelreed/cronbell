package com.steelreed.cronbell.server.history.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * History settings ({@code cronbell.history.*}, DESIGN.md §9).
 *
 * @param retention how long pings and finished deliveries are kept; must be positive
 * @param batchSize rows deleted per statement
 */
@ConfigurationProperties("cronbell.history")
public record HistoryProperties(@DefaultValue("90d") Duration retention, @DefaultValue("5000") int batchSize) {

	public HistoryProperties {
		if (retention == null || retention.isZero() || retention.isNegative()) {
			throw new IllegalArgumentException(
					"cronbell.history.retention (CRONBELL_HISTORY_RETENTION) must be a positive duration such as 90d, got "
							+ retention);
		}
		if (batchSize < 1) {
			throw new IllegalArgumentException("cronbell.history.batch-size must be at least 1, got " + batchSize);
		}
	}

}
