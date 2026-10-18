/**
 * History: daily pruning of pings, status events and finished deliveries older than the
 * retention, under a lease so one instance does the work. Has no API.
 */
@ApplicationModule(displayName = "History", allowedDependencies = { "pings", "checks", "alerts", "shared :: database" })
package com.steelreed.cronbell.server.history;

import org.springframework.modulith.ApplicationModule;
