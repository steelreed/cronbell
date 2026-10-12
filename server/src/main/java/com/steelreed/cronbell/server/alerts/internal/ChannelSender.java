package com.steelreed.cronbell.server.alerts.internal;

/** Sends one alert to one channel of its type. */
interface ChannelSender {

	ChannelType type();

	/**
	 * Sends the alert.
	 * @param deliveryId stable across retries of the same delivery
	 * @throws DeliveryException if the receiver did not accept it
	 */
	void send(Channel channel, Alert alert, String deliveryId) throws DeliveryException;

}
