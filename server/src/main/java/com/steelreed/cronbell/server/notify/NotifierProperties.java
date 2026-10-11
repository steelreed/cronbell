package com.steelreed.cronbell.server.notify;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the alert sender ({@code cronbell.notifier.*}).
 *
 * @param interval how often the sender looks for due deliveries
 * @param connectTimeout HTTP and SMTP connect timeout
 * @param readTimeout HTTP read and SMTP read/write timeout
 * @param telegramApiUrl base URL of the Telegram Bot API (tests point it at a stub)
 */
@ConfigurationProperties("cronbell.notifier")
public record NotifierProperties(@DefaultValue("PT5S") Duration interval, @DefaultValue("PT5S") Duration connectTimeout,
		@DefaultValue("PT10S") Duration readTimeout, @DefaultValue("https://api.telegram.org") String telegramApiUrl) {

}
