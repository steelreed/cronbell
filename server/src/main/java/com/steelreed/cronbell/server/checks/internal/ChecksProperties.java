package com.steelreed.cronbell.server.checks.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Check settings ({@code cronbell.checks.*}, DESIGN.md §6, Projects and invites).
 *
 * @param maxPerProject the most checks a project may have
 * ({@code CRONBELL_PROJECT_MAX_CHECKS}); null for no limit
 */
@ConfigurationProperties("cronbell.checks")
public record ChecksProperties(Integer maxPerProject) {

	public ChecksProperties {
		if (maxPerProject != null && maxPerProject < 1) {
			throw new IllegalArgumentException(
					"cronbell.checks.max-per-project (CRONBELL_PROJECT_MAX_CHECKS) must be at least 1, or unset for no limit, got "
							+ maxPerProject);
		}
	}

}
