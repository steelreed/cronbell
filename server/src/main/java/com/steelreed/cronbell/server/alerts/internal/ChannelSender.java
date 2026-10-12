package com.steelreed.cronbell.server.alerts.internal;

/** Sends one alert to one channel of its type. */
sealed interface ChannelSender permits EmailSender, SlackSender, TelegramSender, WebhookSender {

	ChannelType type();

	/**
	 * Sends the alert.
	 * @param deliveryId stable across retries of the same delivery
	 * @throws DeliveryException if the receiver did not accept it
	 */
	void send(Channel channel, Alert alert, String deliveryId) throws DeliveryException;

}
