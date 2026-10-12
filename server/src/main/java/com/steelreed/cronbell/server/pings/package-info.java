/**
 * Pings: the {@code /ping} endpoint compatible with Healthchecks.io, its rate limit, ping
 * storage and the recent pings of a check in the management API. Each ping is applied to
 * its check through {@link com.steelreed.cronbell.server.checks.CheckService}.
 * <p>
 * API: {@link com.steelreed.cronbell.server.pings.Ping} and
 * {@link com.steelreed.cronbell.server.pings.PingHistory}.
 */
@ApplicationModule(displayName = "Pings",
		allowedDependencies = { "checks", "accounts", "shared :: database", "shared :: web" })
package com.steelreed.cronbell.server.pings;

import org.springframework.modulith.ApplicationModule;
