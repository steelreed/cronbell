package com.steelreed.cronbell.server.alerts.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.steelreed.cronbell.server.checks.CheckEvent;
import com.steelreed.cronbell.server.checks.CheckService;
import com.steelreed.cronbell.server.shared.database.Ids;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Queues one delivery per channel the check alerts when it changes status (DESIGN.md §8):
 * the channels it picked, or every channel of the project. Runs before the commit, in the
 * transaction that changed the check, so the alert is stored if and only if the change
 * is.
 */
@Component
class AlertOutbox {

	private final ChannelRepository channels;

	private final DeliveryRepository deliveries;

	private final AlertFactory alerts;

	private final CheckService checks;

	private final Clock clock;

	AlertOutbox(ChannelRepository channels, DeliveryRepository deliveries, AlertFactory alerts, CheckService checks,
			Clock clock) {
		this.channels = channels;
		this.deliveries = deliveries;
		this.alerts = alerts;
		this.checks = checks;
		this.clock = clock;
	}

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	void on(CheckEvent event) {
		List<Channel> targets = this.channels.findAllByProject(event.projectId());
		List<String> picked = this.checks.channels(event.checkId());
		if (!picked.isEmpty()) {
			targets = targets.stream().filter((channel) -> picked.contains(channel.id())).toList();
		}
		if (targets.isEmpty()) {
			return;
		}
		Alert alert = this.alerts.create(event);
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MILLIS);
		for (Channel channel : targets) {
			this.deliveries.insert(Ids.newId(), channel.id(), event.checkId(), alert, now);
		}
	}

}
