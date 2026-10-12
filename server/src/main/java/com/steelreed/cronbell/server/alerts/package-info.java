/**
 * Alerts: channels (email, Telegram, Slack, webhooks), the delivery outbox filled from
 * {@link com.steelreed.cronbell.server.checks.CheckEvent} in the transaction that changed
 * the check, the dispatcher that sends and retries deliveries, and message rendering per
 * channel type.
 * <p>
 * API: {@link com.steelreed.cronbell.server.alerts.DeliveryHistory}.
 */
@ApplicationModule(displayName = "Alerts",
		allowedDependencies = { "checks", "pings", "accounts", "shared :: database", "shared :: web" })
package com.steelreed.cronbell.server.alerts;

import org.springframework.modulith.ApplicationModule;
