package com.steelreed.cronbell.server.notify;

import java.time.Instant;
import java.util.Map;

import com.steelreed.cronbell.server.check.CheckStatus;
import com.steelreed.cronbell.server.check.DownReason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageRendererTests {

	private static final Instant AT = Instant.parse("2026-01-04T20:10:00Z");

	private static Alert missed(String log) {
		return new Alert(Alert.Kind.DOWN, AT, "nightly-backup", "Nightly backup", CheckStatus.DOWN, DownReason.MISSED,
				"Asia/Ho_Chi_Minh", Instant.parse("2026-01-04T20:00:00Z"), 1800L, null, null, null,
				Instant.parse("2026-01-03T20:01:00Z"), log,
				(log != null) ? Instant.parse("2026-01-03T20:01:00Z") : null,
				"https://cronbell.example.com/checks/nightly-backup");
	}

	@Test
	void missedRunAsPlainText() {
		assertThat(MessageRenderer.plainText(missed("rows: 42 <done> & ok"))).isEqualTo("""
				[Cronbell] nightly-backup is DOWN: missed run
				Check: Nightly backup (nightly-backup)
				Expected at: 2026-01-05 03:00 Asia/Ho_Chi_Minh (grace 30m)
				Last success: 2026-01-04 03:01 Asia/Ho_Chi_Minh

				Log (2026-01-04 03:01 Asia/Ho_Chi_Minh):
				rows: 42 <done> & ok

				https://cronbell.example.com/checks/nightly-backup
				""");
	}

	@Test
	void emailSubjectIsTheFirstLine() {
		Alert alert = missed(null);
		assertThat(MessageRenderer.emailSubject(alert)).isEqualTo("[Cronbell] nightly-backup is DOWN: missed run");
		assertThat(MessageRenderer.emailBody(alert)).startsWith("Check: Nightly backup (nightly-backup)\n")
			.doesNotContain("Log");
	}

	@Test
	void failedAndTooLongAndRecovered() {
		Alert failed = new Alert(Alert.Kind.DOWN, AT, "job", "job", CheckStatus.DOWN, DownReason.FAILED, "UTC", null,
				null, 2, null, null, null, null, null, null);
		assertThat(MessageRenderer.plainText(failed)).isEqualTo("""
				[Cronbell] job is DOWN: run failed
				Check: job
				Failed at: 2026-01-04 20:10 UTC (exit code 2)
				Last success: never
				""");
		Alert tooLong = new Alert(Alert.Kind.DOWN, AT, "job", "job", CheckStatus.DOWN, DownReason.RUNNING_TOO_LONG,
				"UTC", null, null, null, Instant.parse("2026-01-04T19:00:15Z"), 3600L, null, null, null, null);
		assertThat(MessageRenderer.plainText(tooLong)).contains("is DOWN: run is taking too long\n")
			.contains("Started at: 2026-01-04 19:00:15 UTC, still running after 1h\n");
		Alert up = new Alert(Alert.Kind.UP, AT, "job", "job", CheckStatus.UP, null, "UTC",
				Instant.parse("2026-01-04T21:00:00Z"), null, null, null, null, AT, null, null, null);
		assertThat(MessageRenderer.plainText(up)).isEqualTo("""
				[Cronbell] job is UP again
				Check: job
				Recovered at: 2026-01-04 20:10 UTC
				Next run expected: 2026-01-04 21:00 UTC
				""");
	}

	@Test
	void telegramEscapesHtml() {
		String html = MessageRenderer.telegramHtml(missed("a < b && c > \"d\""));
		assertThat(html).startsWith("<b>[Cronbell] nightly-backup is DOWN: missed run</b>\n")
			.contains("<pre>a &lt; b &amp;&amp; c &gt; &quot;d&quot;</pre>")
			.doesNotContain("a < b");
	}

	@Test
	void slackEscapesMrkdwnControlCharacters() {
		String text = MessageRenderer.slackText(missed("<!channel> & ```x```"));
		assertThat(text).startsWith("*[Cronbell] nightly-backup is DOWN: missed run*\n")
			.contains("```\n&lt;!channel&gt; &amp; '''x'''\n```")
			.endsWith("\n<https://cronbell.example.com/checks/nightly-backup>");
	}

	@Test
	void webhookPayloadVersion1() {
		Map<String, Object> payload = MessageRenderer.webhookPayload(missed("log"), "d-1");
		assertThat(payload).containsEntry("version", 1)
			.containsEntry("id", "d-1")
			.containsEntry("event", "check.down")
			.containsEntry("at", "2026-01-04T20:10:00Z")
			.containsEntry("log", "log")
			.containsKey("text");
		@SuppressWarnings("unchecked")
		Map<String, Object> check = (Map<String, Object>) payload.get("check");
		assertThat(check).containsEntry("slug", "nightly-backup")
			.containsEntry("status", "DOWN")
			.containsEntry("downReason", "MISSED")
			.containsEntry("timeZone", "Asia/Ho_Chi_Minh")
			.containsEntry("expectedAt", "2026-01-04T20:00:00Z")
			.containsEntry("lastSuccessAt", "2026-01-03T20:01:00Z")
			.containsEntry("url", "https://cronbell.example.com/checks/nightly-backup");
	}

	@Test
	void testMessage() {
		Alert test = Alert.test(AT);
		assertThat(MessageRenderer.plainText(test)).startsWith("[Cronbell] Test message\n");
		assertThat(MessageRenderer.webhookPayload(test, "t-1")).containsEntry("event", "test")
			.containsEntry("check", null);
	}

	@Test
	void durations() {
		assertThat(MessageRenderer.duration(0)).isEqualTo("0s");
		assertThat(MessageRenderer.duration(45)).isEqualTo("45s");
		assertThat(MessageRenderer.duration(5400)).isEqualTo("1h 30m");
		assertThat(MessageRenderer.duration(90000)).isEqualTo("1d 1h");
	}

	@Test
	void logExcerptKeepsTheEnd() {
		String log = "a".repeat(500) + "b".repeat(1000);
		assertThat(AlertFactory.excerpt(log)).isEqualTo("…" + "b".repeat(1000));
		assertThat(AlertFactory.excerpt("  short\n")).isEqualTo("short");
	}

}
