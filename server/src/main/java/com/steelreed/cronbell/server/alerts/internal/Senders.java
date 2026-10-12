package com.steelreed.cronbell.server.alerts.internal;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Picks the sender of a channel's type, and makes sure no error message carries a secret
 * of the channel.
 */
@Component
class Senders {

	private static final Logger logger = LoggerFactory.getLogger(Senders.class);

	private final Map<ChannelType, ChannelSender> byType = new EnumMap<>(ChannelType.class);

	Senders(List<ChannelSender> senders) {
		for (ChannelSender sender : senders) {
			this.byType.put(sender.type(), sender);
		}
	}

	void send(Channel channel, Alert alert, String deliveryId) throws DeliveryException {
		ChannelSender sender = this.byType.get(channel.type());
		if (sender == null) {
			throw DeliveryException.permanent("no sender for " + channel.type().id());
		}
		try {
			sender.send(channel, alert, deliveryId);
		}
		catch (DeliveryException ex) {
			throw new DeliveryException(ChannelConfigs.scrub(channel, ex.getMessage()), ex.retryable(),
					ex.retryAfter());
		}
		catch (RuntimeException ex) {
			// Log the type only: the message of an unexpected exception may hold a URL.
			logger.warn("Unexpected {} sending to {}", ex.getClass().getName(), channel);
			throw DeliveryException.retryable("internal error: " + ex.getClass().getSimpleName());
		}
	}

}
