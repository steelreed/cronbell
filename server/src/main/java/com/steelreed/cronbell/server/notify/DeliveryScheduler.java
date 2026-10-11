package com.steelreed.cronbell.server.notify;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.steelreed.cronbell.server.check.CheckEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs the {@link DeliveryDispatcher} every {@code cronbell.notifier.interval}, and right
 * after a status change is committed so alerts leave at once. Runs never overlap on one
 * instance. Off when {@code cronbell.scheduling.enabled=false} (tests run it by hand).
 */
@Component
@ConditionalOnProperty(name = "cronbell.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class DeliveryScheduler implements DisposableBean {

	private static final Logger logger = LoggerFactory.getLogger(DeliveryScheduler.class);

	private final DeliveryDispatcher dispatcher;

	private final ExecutorService executor = Executors
		.newSingleThreadExecutor(Thread.ofVirtual().name("cronbell-notifier").factory());

	private final AtomicBoolean queued = new AtomicBoolean();

	DeliveryScheduler(DeliveryDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	@Scheduled(fixedDelayString = "${cronbell.notifier.interval:PT5S}",
			initialDelayString = "${cronbell.notifier.interval:PT5S}")
	void scheduledRun() {
		wake();
	}

	@TransactionalEventListener
	void onCommitted(CheckEvent event) {
		wake();
	}

	/** Queues a run unless one is already waiting. */
	void wake() {
		if (this.queued.compareAndSet(false, true)) {
			this.executor.execute(() -> {
				this.queued.set(false);
				try {
					this.dispatcher.runOnce();
				}
				catch (RuntimeException ex) {
					logger.error("Notifier run failed", ex);
				}
			});
		}
	}

	@Override
	public void destroy() {
		this.executor.shutdownNow();
	}

}
