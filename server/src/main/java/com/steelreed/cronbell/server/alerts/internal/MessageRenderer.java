package com.steelreed.cronbell.server.alerts.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an {@link Alert} into the text of each channel (DESIGN.md §8): plain text for
 * email, HTML for Telegram, mrkdwn for Slack, JSON for webhooks. Pure.
 */
public final class MessageRenderer {

	private static final DateTimeFormatter MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/** Version of the webhook JSON schema. */
	public static final int WEBHOOK_VERSION = 1;

	/**
	 * The parts of a message: a title, detail lines, an optional log excerpt and link.
	 */
	public record Message(String title, List<String> lines, String logLabel, String log, String url) {
	}

	private MessageRenderer() {
	}

	public static Message message(Alert alert) {
		ZoneId zone = ZoneId.of(alert.timeZone());
		List<String> lines = new ArrayList<>();
		String title;
		switch (alert.kind()) {
			case TEST -> {
				title = "[Cronbell] Test message";
				lines.add("This channel works. Cronbell sends an alert here when a check goes DOWN or comes back UP.");
			}
			case UP -> {
				title = "[Cronbell] " + alert.slug() + " is UP again";
				lines.add(checkLine(alert));
				lines.add("Recovered at: " + time(alert.at(), zone));
				if (alert.expectedAt() != null) {
					lines.add("Next run expected: " + time(alert.expectedAt(), zone));
				}
			}
			case DOWN -> {
				title = "[Cronbell] " + alert.slug() + " is DOWN: " + switch (alert.downReason()) {
					case MISSED -> "missed run";
					case FAILED -> "run failed";
					case RUNNING_TOO_LONG -> "run is taking too long";
				};
				lines.add(checkLine(alert));
				switch (alert.downReason()) {
					case MISSED ->
						lines.add("Expected at: " + time(alert.expectedAt(), zone) + ((alert.graceSeconds() != null)
								? " (grace " + duration(alert.graceSeconds()) + ")" : ""));
					case FAILED -> lines.add("Failed at: " + time(alert.at(), zone)
							+ ((alert.exitCode() != null) ? " (exit code " + alert.exitCode() + ")" : ""));
					case RUNNING_TOO_LONG -> lines
						.add("Started at: " + time(alert.runStartedAt(), zone) + ((alert.maxRuntimeSeconds() != null)
								? ", still running after " + duration(alert.maxRuntimeSeconds()) : ""));
				}
				lines.add("Last success: "
						+ ((alert.lastSuccessAt() != null) ? time(alert.lastSuccessAt(), zone) : "never"));
			}
			default -> throw new IllegalStateException();
		}
		String logLabel = (alert.log() != null)
				? "Log" + ((alert.logAt() != null) ? " (" + time(alert.logAt(), zone) + ")" : "") + ":" : null;
		return new Message(title, List.copyOf(lines), logLabel, alert.log(), alert.url());
	}

	/** The whole message as plain text, title first. */
	public static String plainText(Alert alert) {
		Message m = message(alert);
		return m.title() + "\n" + emailBody(alert);
	}

	public static String emailSubject(Alert alert) {
		return message(alert).title();
	}

	public static String emailBody(Alert alert) {
		Message m = message(alert);
		StringBuilder text = new StringBuilder(String.join("\n", m.lines()));
		if (m.log() != null) {
			text.append("\n\n").append(m.logLabel()).append('\n').append(m.log());
		}
		if (m.url() != null) {
			text.append("\n\n").append(m.url());
		}
		return text.append('\n').toString();
	}

	/** Telegram {@code sendMessage} text with {@code parse_mode=HTML}. */
	public static String telegramHtml(Alert alert) {
		Message m = message(alert);
		StringBuilder text = new StringBuilder("<b>").append(html(m.title())).append("</b>");
		for (String line : m.lines()) {
			text.append('\n').append(html(line));
		}
		if (m.log() != null) {
			text.append("\n\n").append(html(m.logLabel())).append("\n<pre>").append(html(m.log())).append("</pre>");
		}
		if (m.url() != null) {
			text.append("\n\n").append(html(m.url()));
		}
		return text.toString();
	}

	/** Slack {@code text} in mrkdwn. */
	public static String slackText(Alert alert) {
		Message m = message(alert);
		StringBuilder text = new StringBuilder("*").append(slack(m.title())).append('*');
		for (String line : m.lines()) {
			text.append('\n').append(slack(line));
		}
		if (m.log() != null) {
			text.append("\n")
				.append(slack(m.logLabel()))
				.append("\n```\n")
				.append(slack(m.log()).replace("```", "'''"))
				.append("\n```");
		}
		if (m.url() != null) {
			text.append("\n<").append(m.url()).append(">");
		}
		return text.toString();
	}

	/**
	 * The webhook body (version {@value #WEBHOOK_VERSION}), as a map to serialize. Keeps
	 * null values so the schema is stable.
	 */
	public static Map<String, Object> webhookPayload(Alert alert, String deliveryId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("version", WEBHOOK_VERSION);
		body.put("id", deliveryId);
		body.put("event", switch (alert.kind()) {
			case DOWN -> "check.down";
			case UP -> "check.up";
			case TEST -> "test";
		});
		body.put("at", alert.at().toString());
		body.put("text", plainText(alert));
		if (alert.kind() == Alert.Kind.TEST) {
			body.put("check", null);
		}
		else {
			Map<String, Object> check = new LinkedHashMap<>();
			check.put("slug", alert.slug());
			check.put("name", alert.name());
			check.put("status", alert.status().name());
			check.put("downReason", (alert.downReason() != null) ? alert.downReason().name() : null);
			check.put("timeZone", alert.timeZone());
			check.put("expectedAt", iso(alert.expectedAt()));
			check.put("lastSuccessAt", iso(alert.lastSuccessAt()));
			check.put("url", alert.url());
			body.put("check", check);
		}
		body.put("log", alert.log());
		return body;
	}

	private static String checkLine(Alert alert) {
		boolean named = alert.name() != null && !alert.name().equals(alert.slug());
		return "Check: " + (named ? alert.name() + " (" + alert.slug() + ")" : alert.slug());
	}

	static String time(Instant instant, ZoneId zone) {
		if (instant == null) {
			return "unknown";
		}
		ZonedDateTime local = instant.atZone(zone);
		DateTimeFormatter format = (local.getSecond() == 0) ? MINUTES : SECONDS;
		return format.format(local) + " " + zone.getId();
	}

	static String duration(long seconds) {
		Duration d = Duration.ofSeconds(seconds);
		List<String> parts = new ArrayList<>();
		if (d.toDays() > 0) {
			parts.add(d.toDays() + "d");
		}
		if (d.toHoursPart() > 0) {
			parts.add(d.toHoursPart() + "h");
		}
		if (d.toMinutesPart() > 0) {
			parts.add(d.toMinutesPart() + "m");
		}
		if (d.toSecondsPart() > 0 || parts.isEmpty()) {
			parts.add(d.toSecondsPart() + "s");
		}
		return String.join(" ", parts);
	}

	private static String iso(Instant instant) {
		return (instant != null) ? instant.toString() : null;
	}

	/** Escapes text for Telegram's HTML parse mode. */
	static String html(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	/** Escapes the control characters of Slack mrkdwn. */
	static String slack(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

}
