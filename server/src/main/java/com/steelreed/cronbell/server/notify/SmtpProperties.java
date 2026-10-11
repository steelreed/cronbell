package com.steelreed.cronbell.server.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Server-wide SMTP settings for email channels ({@code CRONBELL_SMTP_*}). Port 465 uses
 * implicit TLS; other ports use STARTTLS unless {@code starttls} is false.
 */
@ConfigurationProperties("cronbell.smtp")
public record SmtpProperties(String host, @DefaultValue("587") int port, String username, String password, String from,
		@DefaultValue("true") boolean starttls) {

	public boolean configured() {
		return hasText(this.host) && hasText(sender());
	}

	/** The From address: {@code from}, or the username when it is an address. */
	public String sender() {
		if (hasText(this.from)) {
			return this.from.trim();
		}
		return (hasText(this.username) && this.username.contains("@")) ? this.username.trim() : null;
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	/** Never prints the password. */
	@Override
	public String toString() {
		return "SmtpProperties[host=" + this.host + ", port=" + this.port + "]";
	}

}
