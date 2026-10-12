/**
 * Checks: the jobs being watched, their schedules (cron with time zones, or a period),
 * the state machine of DESIGN.md §3 and §4, and the detector that finds missed and
 * too-long runs. Publishes {@link com.steelreed.cronbell.server.checks.CheckEvent} when a
 * check goes DOWN or comes back UP.
 * <p>
 * API: the check model, {@link com.steelreed.cronbell.server.checks.CheckEvent} and
 * {@link com.steelreed.cronbell.server.checks.CheckService}.
 */
@ApplicationModule(displayName = "Checks", allowedDependencies = { "accounts", "shared :: database", "shared :: web" })
package com.steelreed.cronbell.server.checks;

import org.springframework.modulith.ApplicationModule;
