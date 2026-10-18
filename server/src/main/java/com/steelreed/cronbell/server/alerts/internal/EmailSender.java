package com.steelreed.cronbell.server.alerts.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;

import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Plain-text email through the server's SMTP settings ({@code CRONBELL_SMTP_*}). */
@Component
final class EmailSender implements ChannelSender {

	private final SmtpProperties smtp;

	private final NotifierProperties notifier;

	private final JavaMailSenderImpl mail;

	EmailSender(SmtpProperties smtp, NotifierProperties notifier) {
		this.smtp = smtp;
		this.notifier = notifier;
		this.mail = smtp.configured() ? createMailSender(smtp, notifier) : null;
	}

	private static JavaMailSenderImpl createMailSender(SmtpProperties smtp, NotifierProperties notifier) {
		JavaMailSenderImpl sender = new JavaMailSenderImpl();
		boolean implicitTls = smtp.port() == 465;
		String protocol = implicitTls ? "smtps" : "smtp";
		sender.setProtocol(protocol);
		sender.setHost(smtp.host().trim());
		sender.setPort(smtp.port());
		sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
		if (StringUtils.hasText(smtp.username())) {
			sender.setUsername(smtp.username().trim());
			sender.setPassword(smtp.password());
		}
		Properties properties = new Properties();
		String prefix = "mail." + protocol + ".";
		properties.setProperty(prefix + "auth", Boolean.toString(StringUtils.hasText(smtp.username())));
		properties.setProperty(prefix + "connectiontimeout", Long.toString(notifier.connectTimeout().toMillis()));
		properties.setProperty(prefix + "timeout", Long.toString(notifier.readTimeout().toMillis()));
		properties.setProperty(prefix + "writetimeout", Long.toString(notifier.readTimeout().toMillis()));
		if (!implicitTls && smtp.starttls()) {
			properties.setProperty(prefix + "starttls.enable", "true");
			properties.setProperty(prefix + "starttls.required", "true");
		}
		sender.setJavaMailProperties(properties);
		return sender;
	}

	boolean configured() {
		return this.mail != null;
	}

	@Override
	public ChannelType type() {
		return ChannelType.EMAIL;
	}

	@Override
	public void send(Channel channel, Alert alert, String deliveryId) throws DeliveryException {
		if (this.mail == null) {
			throw DeliveryException.permanent("SMTP is not configured (CRONBELL_SMTP_HOST)");
		}
		try {
			this.mail.send(message(channel.get(ChannelConfigs.ADDRESS), alert));
		}
		catch (MailParseException ex) {
			throw DeliveryException.permanent(scrub(DeliveryErrors.withReason("Could not build the email", ex)));
		}
		catch (MailAuthenticationException ex) {
			throw DeliveryException.retryable("SMTP server " + this.smtp.host().trim()
					+ " rejected the user name or password (CRONBELL_SMTP_USERNAME, CRONBELL_SMTP_PASSWORD)");
		}
		catch (MailSendException ex) {
			boolean rejected = ex.getFailedMessages()
				.values()
				.stream()
				.anyMatch((cause) -> cause instanceof SendFailedException);
			if (rejected) {
				throw DeliveryException
					.permanent(scrub(DeliveryErrors.withReason("SMTP server rejected the message", ex)));
			}
			throw DeliveryException.retryable(describe(ex));
		}
		catch (MailException ex) {
			throw DeliveryException.retryable(describe(ex));
		}
	}

	MimeMessage message(String to, Alert alert) {
		MimeMessage message = this.mail.createMimeMessage();
		try {
			MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
			helper.setFrom(this.smtp.sender());
			helper.setTo(to);
			helper.setSubject(MessageRenderer.emailSubject(alert));
			helper.setText(MessageRenderer.emailBody(alert), false);
			message.setHeader("Auto-Submitted", "auto-generated");
		}
		catch (MessagingException ex) {
			throw new MailParseException(ex);
		}
		return message;
	}

	/** A network error, or what the SMTP server answered. */
	private String describe(MailException ex) {
		String host = this.smtp.host().trim();
		if (DeliveryErrors.find(ex, IOException.class) != null) {
			return scrub(DeliveryErrors.network(ex, host, this.smtp.port(), this.notifier.connectTimeout(),
					this.notifier.readTimeout()));
		}
		return scrub(DeliveryErrors.withReason("SMTP error from " + host, ex));
	}

	/** Without the password, in case the server echoed it. */
	private String scrub(String text) {
		String password = this.smtp.password();
		return (password != null && !password.isEmpty()) ? text.replace(password, "****") : text;
	}

}
