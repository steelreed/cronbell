package com.steelreed.cronbell.server.checks.internal;

import com.steelreed.cronbell.server.checks.CheckEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Logs status changes once committed. The notifier listens too (notify.AlertOutbox). */
@Component
class CheckEventLogger {

	private static final Logger logger = LoggerFactory.getLogger(CheckEventLogger.class);

	@TransactionalEventListener
	void on(CheckEvent event) {
		switch (event) {
			case CheckEvent.WentDown down ->
				logger.info("Check {} ({}) is DOWN: {}", down.slug(), down.checkId(), down.reason());
			case CheckEvent.Recovered up -> logger.info("Check {} ({}) is UP again", up.slug(), up.checkId());
		}
	}

}
