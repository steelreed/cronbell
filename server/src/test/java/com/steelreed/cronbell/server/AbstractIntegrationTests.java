package com.steelreed.cronbell.server;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;
import com.steelreed.cronbell.server.accounts.internal.AccountService;
import com.steelreed.cronbell.server.accounts.internal.AdminBootstrap;
import com.steelreed.cronbell.server.accounts.internal.ProjectService;
import com.steelreed.cronbell.server.alerts.internal.Channel;
import com.steelreed.cronbell.server.alerts.internal.ChannelRepository;
import com.steelreed.cronbell.server.alerts.internal.ChannelType;
import com.steelreed.cronbell.server.alerts.internal.DeliveryDispatcher;
import com.steelreed.cronbell.server.alerts.internal.WebhookSigner;
import com.steelreed.cronbell.server.checks.CheckEvent;
import com.steelreed.cronbell.server.checks.internal.Detector;
import com.steelreed.cronbell.server.history.internal.HistoryPruner;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests over HTTP. Subclasses run them against SQLite and PostgreSQL. The
 * clock is fixed and moved by hand, and the detector is run by hand.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "cronbell.scheduling.enabled=false",
				"cronbell.bootstrap.api-key=" + AbstractIntegrationTests.API_KEY,
				"cronbell.base-url=https://cronbell.example.com/", "cronbell.notifier.read-timeout=PT1S",
				"cronbell.history.batch-size=2", "server.forward-headers-strategy=native" })
@Import(AbstractIntegrationTests.EventCollector.class)
abstract class AbstractIntegrationTests {

	static final String API_KEY = "cb_integration_test_key_0123456789";

	static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-01-05T10:00:00Z"));

	/** Stands in for Slack, Telegram and webhook receivers. */
	static final FakeReceiver RECEIVER = new FakeReceiver();

	static final GreenMail MAIL = startMail();

	@DynamicPropertySource
	static void notifier(DynamicPropertyRegistry registry) {
		registry.add("cronbell.notifier.telegram-api-url", () -> RECEIVER.url("/tg"));
		registry.add("cronbell.smtp.host", () -> "localhost");
		registry.add("cronbell.smtp.port", () -> MAIL.getSmtp().getPort());
		registry.add("cronbell.smtp.from", () -> "cronbell@test.local");
		registry.add("cronbell.smtp.starttls", () -> "false");
	}

	@TestBean
	Clock clock;

	static Clock clock() {
		return CLOCK;
	}

	@LocalServerPort
	int port;

	@Autowired
	EventCollector events;

	@Autowired
	Detector detector;

	@Autowired
	ProjectService projects;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	DeliveryDispatcher dispatcher;

	@Autowired
	HistoryPruner pruner;

	@Autowired
	ChannelRepository channelRepository;

	@Autowired
	AccountService accounts;

	RestClient http;

	@BeforeEach
	void setUp() {
		CLOCK.set("2026-01-05T10:00:00Z");
		// The clock is shared by both test classes, so it may have been ahead when the
		// Default project was made; keep it the oldest, as on a real first start.
		this.jdbc.sql("UPDATE projects SET created_at = 0 WHERE id = ?")
			.param(this.projects.findProjectIdByApiKey(API_KEY).orElseThrow())
			.update();
		this.events.clear();
		this.http = RestClient.builder()
			.baseUrl("http://localhost:" + this.port)
			.defaultStatusHandler((status) -> true, (request, response) -> {
			})
			.build();
	}

	// --- Ping API ---

	@Test
	void unknownPingKeyIs404() {
		assertThat(get("/ping/" + UUID.randomUUID()).getStatusCode().value()).isEqualTo(404);
		assertThat(get("/ping/not-a-uuid").getStatusCode().value()).isEqualTo(404);
	}

	@Test
	void unknownActionIs404() {
		String key = createCheck(uniqueSlug(), "{\"cron\":\"0 * * * *\"}");
		assertThat(get("/ping/" + key + "/nope").getStatusCode().value()).isEqualTo(404);
		assertThat(get("/ping/" + key + "/256").getStatusCode().value()).isEqualTo(404);
		assertThat(pings(key)).isEmpty();
	}

	@Test
	void firstSuccessMovesNewToUp() {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"cron\":\"0 * * * *\"}");
		assertThat(field(slug, "status")).isEqualTo("NEW");
		CLOCK.set("2026-01-05T10:00:05Z");
		ResponseEntity<String> response = get("/ping/" + key);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody()).isEqualTo("OK");
		assertThat(field(slug, "status")).isEqualTo("UP");
		assertThat(field(slug, "lastSuccessAt")).isEqualTo("2026-01-05T10:00:05Z");
		assertThat(field(slug, "nextDueAt")).isEqualTo("2026-01-05T11:00:00Z");
		assertThat(this.events.forSlug(slug)).isEmpty();
	}

	@Test
	void postStoresBodyRidAndClientDetails() {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"cron\":\"0 * * * *\"}");
		String rid = UUID.randomUUID().toString();
		ResponseEntity<String> response = this.http.post()
			.uri("/ping/" + key + "/start?rid=" + rid)
			.header(HttpHeaders.USER_AGENT, "curl/8.0")
			.contentType(MediaType.TEXT_PLAIN)
			.body("starting backup")
			.retrieve()
			.toEntity(String.class);
		assertThat(response.getBody()).isEqualTo("OK");
		Map<String, Object> ping = pings(key).getFirst();
		assertThat(ping).containsEntry("kind", "START")
			.containsEntry("rid", rid)
			.containsEntry("body", "starting backup")
			.containsEntry("user_agent", "curl/8.0");
		assertThat(ping.get("remote_addr")).isNotNull();
		assertThat(field(slug, "lastStartAt")).isEqualTo("2026-01-05T10:00:00Z");
	}

	@Test
	void formEncodedBodyIsStoredAsText() {
		String key = createCheck(uniqueSlug(), "{\"cron\":\"0 * * * *\"}");
		this.http.post()
			.uri("/ping/" + key + "/log?rid=r1")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body("rows=42&status=done")
			.retrieve()
			.toEntity(String.class);
		assertThat(pings(key).getFirst()).containsEntry("body", "rows=42&status=done").containsEntry("rid", "r1");
	}

	@Test
	void bodyIsTruncatedTo10Kilobytes() {
		String key = createCheck(uniqueSlug(), "{\"cron\":\"0 * * * *\"}");
		String body = "x".repeat(11 * 1024);
		this.http.post()
			.uri("/ping/" + key + "/fail")
			.contentType(MediaType.TEXT_PLAIN)
			.body(body)
			.retrieve()
			.toEntity(String.class);
		assertThat((String) pings(key).getFirst().get("body")).hasSize(10 * 1024);
	}

	@Test
	void failGoesDownAndSuccessRecovers() {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"cron\":\"0 * * * *\"}");
		get("/ping/" + key);
		CLOCK.advance(Duration.ofHours(1));
		assertThat(post("/ping/" + key + "/fail").getBody()).isEqualTo("OK");
		assertThat(field(slug, "status")).isEqualTo("DOWN");
		assertThat(field(slug, "downReason")).isEqualTo("FAILED");
		CLOCK.advance(Duration.ofHours(1));
		get("/ping/" + key);
		assertThat(field(slug, "status")).isEqualTo("UP");
		assertThat(field(slug, "downReason")).isNull();
		List<CheckEvent> events = this.events.forSlug(slug);
		assertThat(events).hasSize(2);
		assertThat(events.get(0)).isInstanceOfSatisfying(CheckEvent.WentDown.class,
				(down) -> assertThat(down.reason().name()).isEqualTo("FAILED"));
		assertThat(events.get(1)).isInstanceOf(CheckEvent.Recovered.class);
	}

	@Test
	void exitCodes() {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"cron\":\"0 * * * *\"}");
		get("/ping/" + key + "/0");
		assertThat(field(slug, "status")).isEqualTo("UP");
		CLOCK.advance(Duration.ofSeconds(1));
		get("/ping/" + key + "/3");
		assertThat(field(slug, "status")).isEqualTo("DOWN");
		assertThat(pings(key)).extracting((ping) -> ((Number) ping.get("exit_code")).intValue()).containsExactly(3, 0);
		assertThat(pings(key)).extracting((ping) -> ping.get("kind")).containsExactly("FAIL", "SUCCESS");
	}

	@Test
	void logPingIsStoredWithoutStateChange() {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"cron\":\"0 * * * *\"}");
		get("/ping/" + key + "/log");
		assertThat(field(slug, "status")).isEqualTo("NEW");
		assertThat(field(slug, "lastPingAt")).isNull();
		assertThat(pings(key)).hasSize(1);
	}

	@Test
	void stalePingIsStoredWithoutStateChange() {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"cron\":\"0 * * * *\"}");
		get("/ping/" + key);
		CLOCK.set("2026-01-05T09:59:50Z");
		get("/ping/" + key + "/fail");
		assertThat(field(slug, "status")).isEqualTo("UP");
		assertThat(field(slug, "lastPingAt")).isEqualTo("2026-01-05T10:00:00Z");
		assertThat(pings(key)).hasSize(2);
	}

	@Test
	void earlySuccessMovesDueForward() {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"periodSeconds\":3600}");
		get("/ping/" + key);
		assertThat(field(slug, "nextDueAt")).isEqualTo("2026-01-05T11:00:00Z");
		CLOCK.set("2026-01-05T10:20:00Z");
		get("/ping/" + key);
		assertThat(field(slug, "nextDueAt")).isEqualTo("2026-01-05T11:20:00Z");
	}

	@Test
	void rateLimitIsFivePerSecondPerCheck() {
		String key = createCheck(uniqueSlug(), "{\"cron\":\"0 * * * *\"}");
		for (int i = 0; i < 5; i++) {
			assertThat(get("/ping/" + key + "/log").getStatusCode().value()).isEqualTo(200);
		}
		assertThat(get("/ping/" + key + "/log").getStatusCode().value()).isEqualTo(429);
		assertThat(pings(key)).hasSize(5);
		CLOCK.advance(Duration.ofSeconds(1));
		assertThat(get("/ping/" + key + "/log").getStatusCode().value()).isEqualTo(200);
	}

	// --- Detector ---

	@Test
	void detectorMarksMissedRunsOnceAndRecovers() {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"cron\":\"0 * * * *\",\"graceSeconds\":60}");
		CLOCK.set("2026-01-05T10:00:05Z");
		get("/ping/" + key);
		CLOCK.set("2026-01-05T11:01:00Z");
		this.detector.runOnce();
		assertThat(field(slug, "status")).isEqualTo("UP");
		CLOCK.set("2026-01-05T11:01:01Z");
		this.detector.runOnce();
		assertThat(field(slug, "status")).isEqualTo("DOWN");
		assertThat(field(slug, "downReason")).isEqualTo("MISSED");
		CLOCK.advance(Duration.ofMinutes(5));
		this.detector.runOnce();
		assertThat(this.events.forSlug(slug)).hasSize(1).first().isInstanceOf(CheckEvent.WentDown.class);
		get("/ping/" + key);
		assertThat(field(slug, "status")).isEqualTo("UP");
		assertThat(field(slug, "nextDueAt")).isEqualTo("2026-01-05T12:00:00Z");
		assertThat(this.events.forSlug(slug)).hasSize(2).last().isInstanceOf(CheckEvent.Recovered.class);
	}

	@Test
	void nextRunAtLooksPastAMissedRun() {
		String cron = uniqueSlug();
		String key = createCheck(cron, "{\"cron\":\"0 * * * *\",\"graceSeconds\":60}");
		assertThat(field(cron, "nextRunAt")).isNull();
		CLOCK.set("2026-01-05T10:00:05Z");
		get("/ping/" + key);
		assertThat(field(cron, "nextRunAt")).isEqualTo("2026-01-05T11:00:00Z");
		CLOCK.set("2026-01-05T12:30:00Z");
		this.detector.runOnce();
		assertThat(field(cron, "status")).isEqualTo("DOWN");
		// nextDueAt keeps the missed run; nextRunAt is the next one the schedule expects.
		assertThat(field(cron, "nextDueAt")).isEqualTo("2026-01-05T11:00:00Z");
		assertThat(field(cron, "nextRunAt")).isEqualTo("2026-01-05T13:00:00Z");
		exchange(HttpMethod.POST, "/api/v1/checks/" + cron + "/pause", null, API_KEY);
		assertThat(field(cron, "nextRunAt")).isNull();

		String period = uniqueSlug();
		String periodKey = createCheck(period, "{\"periodSeconds\":600,\"graceSeconds\":60}");
		get("/ping/" + periodKey);
		assertThat(field(period, "nextRunAt")).isEqualTo("2026-01-05T12:40:00Z");
		CLOCK.set("2026-01-05T12:45:00Z");
		assertThat(field(period, "nextRunAt")).isNull();
	}

	@Test
	void detectorMarksRunsThatRunTooLong() {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"cron\":\"0 * * * *\",\"maxRuntimeSeconds\":600}");
		get("/ping/" + key + "/start");
		CLOCK.set("2026-01-05T10:10:00Z");
		this.detector.runOnce();
		assertThat(field(slug, "status")).isEqualTo("UP");
		CLOCK.set("2026-01-05T10:10:01Z");
		this.detector.runOnce();
		assertThat(field(slug, "status")).isEqualTo("DOWN");
		assertThat(field(slug, "downReason")).isEqualTo("RUNNING_TOO_LONG");
	}

	@Test
	void detectorLeavesNewAndPausedChecksAlone() {
		String newSlug = uniqueSlug();
		createCheck(newSlug, "{\"cron\":\"0 * * * *\",\"graceSeconds\":60}");
		String pausedSlug = uniqueSlug();
		String pausedKey = createCheck(pausedSlug, "{\"cron\":\"0 * * * *\",\"graceSeconds\":60}");
		get("/ping/" + pausedKey);
		assertThat(exchange(HttpMethod.POST, "/api/v1/checks/" + pausedSlug + "/pause", null, API_KEY).getStatusCode()
			.value()).isEqualTo(200);
		CLOCK.set("2026-01-06T10:00:00Z");
		this.detector.runOnce();
		assertThat(field(newSlug, "status")).isEqualTo("NEW");
		assertThat(field(pausedSlug, "status")).isEqualTo("PAUSED");
		get("/ping/" + pausedKey);
		assertThat(field(pausedSlug, "status")).isEqualTo("UP");
		assertThat(this.events.forSlug(pausedSlug)).isEmpty();
	}

	@Test
	void runsDueWhileTheServerWasAwayGetANewDeadlineInsteadOfAlerts() {
		Project project = newProject();
		String hook = "/hook/" + UUID.randomUUID();
		createChannel(project, "{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url(hook) + "\"}}");
		String missed = uniqueSlug();
		String missedKey = createCheck(project, missed, "{\"cron\":\"0 * * * *\",\"graceSeconds\":60}");
		String running = uniqueSlug();
		String runningKey = createCheck(project, running, "{\"cron\":\"0 * * * *\",\"maxRuntimeSeconds\":600}");
		String failed = uniqueSlug();
		String failedKey = createCheck(project, failed, "{\"cron\":\"0 * * * *\"}");
		get("/ping/" + missedKey);
		get("/ping/" + runningKey + "/start");
		post("/ping/" + failedKey + "/fail");
		this.dispatcher.runOnce();
		assertThat(RECEIVER.requests(hook)).hasSize(1);
		CLOCK.set("2026-01-05T10:00:30Z");
		this.detector.runOnce();

		// Stopped at 10:00:30, started again at 14:00: the 11:00 run and the end of the
		// open run would have come while nobody listened.
		CLOCK.set("2026-01-05T14:00:00Z");
		assertThat(this.detector.resumeAfterDowntime()).isGreaterThanOrEqualTo(2);
		this.detector.runOnce();
		assertThat(field(project.key(), missed, "status")).isEqualTo("UP");
		assertThat(field(project.key(), missed, "nextDueAt")).isEqualTo("2026-01-05T15:00:00Z");
		assertThat(field(project.key(), running, "status")).isEqualTo("UP");
		assertThat(field(project.key(), failed, "status")).isEqualTo("DOWN");
		assertThat(this.events.forSlug(missed)).isEmpty();
		assertThat(this.events.forSlug(running)).isEmpty();
		this.dispatcher.runOnce();
		assertThat(RECEIVER.requests(hook)).hasSize(1);
		assertThat(this.detector.resumeAfterDowntime()).as("only once per gap").isZero();

		// Still no run after the new deadline: DOWN as usual.
		CLOCK.set("2026-01-05T15:01:01Z");
		this.detector.runOnce();
		assertThat(field(project.key(), missed, "status")).isEqualTo("DOWN");
		assertThat(field(project.key(), missed, "downReason")).isEqualTo("MISSED");
		assertThat(this.events.forSlug(missed)).hasSize(1).first().isInstanceOf(CheckEvent.WentDown.class);
	}

	@Test
	void aShortRestartOrAFirstStartChangesNoDeadline() {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"cron\":\"0 * * * *\",\"graceSeconds\":60}");
		get("/ping/" + key);
		this.jdbc.sql("UPDATE detector_heartbeat SET beat_at = NULL").update();
		CLOCK.set("2026-01-05T11:00:30Z");
		assertThat(this.detector.resumeAfterDowntime()).isZero();
		this.detector.runOnce();
		CLOCK.set("2026-01-05T11:02:30Z");
		assertThat(this.detector.resumeAfterDowntime()).isZero();
		this.detector.runOnce();
		assertThat(field(slug, "status")).isEqualTo("DOWN");
		assertThat(field(slug, "downReason")).isEqualTo("MISSED");
	}

	// --- Management API ---

	@Test
	void putCreatesThenUpdatesKeepingThePingKey() {
		String slug = uniqueSlug();
		ResponseEntity<String> created = put(slug, "{\"name\":\"Nightly backup\",\"cron\":\"0 3 * * *\","
				+ "\"timeZone\":\"Asia/Ho_Chi_Minh\",\"graceSeconds\":600}");
		assertThat(created.getStatusCode().value()).isEqualTo(201);
		String key = JsonPath.read(created.getBody(), "$.pingKey");
		assertThat((String) JsonPath.read(created.getBody(), "$.pingUrl")).endsWith("/ping/" + key);
		assertThat((String) JsonPath.read(created.getBody(), "$.name")).isEqualTo("Nightly backup");
		ResponseEntity<String> updated = put(slug, "{\"periodSeconds\":86400}");
		assertThat(updated.getStatusCode().value()).isEqualTo(200);
		assertThat((String) JsonPath.read(updated.getBody(), "$.pingKey")).isEqualTo(key);
		assertThat((String) JsonPath.read(updated.getBody(), "$.scheduleKind")).isEqualTo("PERIOD");
		assertThat((Object) JsonPath.read(updated.getBody(), "$.cron")).isNull();
		assertThat((String) JsonPath.read(updated.getBody(), "$.name")).isEqualTo(slug);
	}

	@Test
	void changingTheScheduleRecomputesTheDueTime() {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"cron\":\"0 * * * *\"}");
		get("/ping/" + key);
		assertThat(field(slug, "nextDueAt")).isEqualTo("2026-01-05T11:00:00Z");
		CLOCK.set("2026-01-05T10:10:00Z");
		put(slug, "{\"cron\":\"*/30 * * * *\"}");
		assertThat(field(slug, "nextDueAt")).isEqualTo("2026-01-05T10:30:00Z");
	}

	@Test
	void listsChecksOfTheKeysProjectOnly() {
		String slug = uniqueSlug();
		createCheck(slug, "{\"cron\":\"0 * * * *\"}");
		String otherProject = this.projects.createProject("Other");
		String otherKey = this.projects.createApiKey(otherProject, "test");
		ResponseEntity<String> mine = exchange(HttpMethod.GET, "/api/v1/checks", null, API_KEY);
		assertThat((List<String>) JsonPath.read(mine.getBody(), "$[*].slug")).contains(slug);
		ResponseEntity<String> theirs = exchange(HttpMethod.GET, "/api/v1/checks", null, otherKey);
		assertThat(theirs.getStatusCode().value()).isEqualTo(200);
		assertThat((List<String>) JsonPath.read(theirs.getBody(), "$[*].slug")).isEmpty();
	}

	@Test
	void apiNeedsAValidBearerKey() {
		assertThat(exchange(HttpMethod.GET, "/api/v1/checks", null, null).getStatusCode().value()).isEqualTo(401);
		assertThat(exchange(HttpMethod.GET, "/api/v1/checks", null, "cb_wrong_key_wrong_key_wrong").getStatusCode()
			.value()).isEqualTo(401);
		assertThat(
				exchange(HttpMethod.PUT, "/api/v1/checks/x", "{\"cron\":\"0 * * * *\"}", null).getStatusCode().value())
			.isEqualTo(401);
	}

	@Test
	void rejectsInvalidDefinitions() {
		String slug = uniqueSlug();
		assertThat(put(slug, "{\"cron\":\"0 * * * *\",\"periodSeconds\":60}").getStatusCode().value()).isEqualTo(400);
		assertThat(put(slug, "{}").getStatusCode().value()).isEqualTo(400);
		assertThat(put(slug, "{\"cron\":\"0 * * *\"}").getStatusCode().value()).isEqualTo(400);
		assertThat(put(slug, "{\"cron\":\"0 * * * *\",\"timeZone\":\"Mars/Base\"}").getStatusCode().value())
			.isEqualTo(400);
		assertThat(put(slug, "{\"periodSeconds\":10}").getStatusCode().value()).isEqualTo(400);
		assertThat(put("bad slug!", "{\"periodSeconds\":60}").getStatusCode().value()).isEqualTo(400);
		ResponseEntity<String> problem = put(slug, "{\"cron\":\"0 * * *\"}");
		assertThat((String) JsonPath.read(problem.getBody(), "$.detail")).contains("5 or 6 fields");
	}

	@Test
	void pausingAnUnknownCheckIs404() {
		assertThat(exchange(HttpMethod.POST, "/api/v1/checks/" + uniqueSlug() + "/pause", null, API_KEY).getStatusCode()
			.value()).isEqualTo(404);
	}

	// --- Alerts ---

	@Test
	void deliversDownAndUpToEveryChannelOfTheProject() throws Exception {
		Project project = newProject();
		String slack = "/slack/" + UUID.randomUUID();
		insertWebhookUrlChannel(project, ChannelType.SLACK, slack);
		String hook = "/hook/" + UUID.randomUUID();
		createChannel(project, "{\"type\":\"webhook\",\"name\":\"Hook\",\"config\":{\"url\":\"" + RECEIVER.url(hook)
				+ "\",\"secret\":\"" + WEBHOOK_SECRET + "\"}}");
		String token = fakeBotToken();
		createChannel(project,
				"{\"type\":\"telegram\",\"config\":{\"botToken\":\"" + token + "\",\"chatId\":\"-100123\"}}");
		String address = "ops-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
		createChannel(project, "{\"type\":\"email\",\"config\":{\"address\":\"" + address + "\"}}");
		String slug = uniqueSlug();
		String key = createCheck(project, slug,
				"{\"name\":\"Nightly backup\",\"cron\":\"0 * * * *\",\"timeZone\":\"Asia/Ho_Chi_Minh\"}");
		get("/ping/" + key);
		CLOCK.set("2026-01-05T11:00:30Z");
		postBody("/ping/" + key + "/3", "disk <full> & broken");
		assertThat(RECEIVER.requests(slack)).isEmpty();

		assertThat(this.dispatcher.runOnce()).isGreaterThanOrEqualTo(4);

		String slackText = JsonPath.read(RECEIVER.requests(slack).getFirst().body(), "$.text");
		assertThat(slackText).startsWith("*[Cronbell] " + slug + " is DOWN: run failed*")
			.contains("Check: Nightly backup (" + slug + ")")
			.contains("Failed at: 2026-01-05 18:00:30 Asia/Ho_Chi_Minh (exit code 3)")
			.contains("disk &lt;full&gt; &amp; broken")
			.contains("<https://cronbell.example.com/checks/" + slug + ">");

		FakeReceiver.Request webhook = RECEIVER.requests(hook).getFirst();
		assertThat((Integer) JsonPath.read(webhook.body(), "$.version")).isEqualTo(1);
		assertThat((String) JsonPath.read(webhook.body(), "$.event")).isEqualTo("check.down");
		assertThat((String) JsonPath.read(webhook.body(), "$.check.slug")).isEqualTo(slug);
		assertThat((String) JsonPath.read(webhook.body(), "$.check.downReason")).isEqualTo("FAILED");
		assertThat((String) JsonPath.read(webhook.body(), "$.log")).isEqualTo("disk <full> & broken");
		assertThat((String) JsonPath.read(webhook.body(), "$.id")).isEqualTo(webhook.header("Cronbell-Delivery"));
		assertThat(webhook.header("Cronbell-Timestamp")).isEqualTo(Long.toString(CLOCK.instant().getEpochSecond()));
		assertThat(webhook.header("Cronbell-Signature")).isEqualTo(WebhookSigner.sign(WEBHOOK_SECRET,
				CLOCK.instant().getEpochSecond(), webhook.body().getBytes(StandardCharsets.UTF_8)));

		FakeReceiver.Request telegram = RECEIVER.requests("/tg/bot" + token + "/sendMessage").getFirst();
		assertThat((String) JsonPath.read(telegram.body(), "$.chat_id")).isEqualTo("-100123");
		assertThat((String) JsonPath.read(telegram.body(), "$.parse_mode")).isEqualTo("HTML");
		assertThat((String) JsonPath.read(telegram.body(), "$.text")).startsWith("<b>[Cronbell] " + slug)
			.contains("<pre>disk &lt;full&gt; &amp; broken</pre>");

		MimeMessage mail = mailTo(address).getFirst();
		assertThat(mail.getSubject()).isEqualTo("[Cronbell] " + slug + " is DOWN: run failed");
		assertThat(mail.getFrom()[0].toString()).isEqualTo("cronbell@test.local");
		assertThat(mail.getContent().toString()).contains("disk <full> & broken");

		CLOCK.advance(Duration.ofMinutes(5));
		get("/ping/" + key);
		assertThat(this.dispatcher.runOnce()).isGreaterThanOrEqualTo(4);
		assertThat((String) JsonPath.read(RECEIVER.requests(slack).getLast().body(), "$.text"))
			.startsWith("*[Cronbell] " + slug + " is UP again*");
		assertThat((String) JsonPath.read(RECEIVER.requests(hook).getLast().body(), "$.event")).isEqualTo("check.up");
		assertThat(mailTo(address)).hasSize(2);

		String channels = exchange(HttpMethod.GET, "/api/v1/channels", null, project.key()).getBody();
		assertThat((List<Object>) JsonPath.read(channels, "$[*].lastDeliveryAt")).hasSize(4).doesNotContainNull();
		assertThat((List<Object>) JsonPath.read(channels, "$[*].lastError")).containsOnlyNulls();
		assertThat((List<Integer>) JsonPath.read(channels, "$[*].pendingDeliveries")).containsOnly(0);
	}

	@Test
	void missedRunAlertNamesTheExpectedTime() {
		Project project = newProject();
		String hook = "/hook/" + UUID.randomUUID();
		createChannel(project, "{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url(hook) + "\"}}");
		String slug = uniqueSlug();
		String key = createCheck(project, slug, "{\"cron\":\"0 * * * *\",\"graceSeconds\":600}");
		get("/ping/" + key);
		CLOCK.set("2026-01-05T11:10:01Z");
		this.detector.runOnce();
		this.dispatcher.runOnce();
		String body = RECEIVER.requests(hook).getFirst().body();
		assertThat((String) JsonPath.read(body, "$.check.downReason")).isEqualTo("MISSED");
		assertThat((String) JsonPath.read(body, "$.check.expectedAt")).isEqualTo("2026-01-05T11:00:00Z");
		assertThat((String) JsonPath.read(body, "$.text")).contains("Expected at: 2026-01-05 11:00 UTC (grace 10m)")
			.contains("Last success: 2026-01-05 10:00 UTC");
		assertThat(RECEIVER.requests(hook).getFirst().header("Cronbell-Signature")).isNull();
	}

	@Test
	void retriesAfterAServerErrorWithTheSameDeliveryId() {
		Project project = newProject();
		String hook = "/hook/" + UUID.randomUUID();
		RECEIVER.script(hook, FakeReceiver.Response.of(500, "oops"));
		String channel = createChannel(project,
				"{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url(hook) + "\"}}");
		goDown(project);
		this.dispatcher.runOnce();
		assertThat(RECEIVER.requests(hook)).hasSize(1);
		String status = exchange(HttpMethod.GET, "/api/v1/channels/" + channel, null, project.key()).getBody();
		assertThat((String) JsonPath.read(status, "$.lastError")).isEqualTo("HTTP 500 from localhost: oops");
		assertThat((Integer) JsonPath.read(status, "$.pendingDeliveries")).isEqualTo(1);
		assertThat((Object) JsonPath.read(status, "$.lastDeliveryAt")).isNull();

		CLOCK.advance(Duration.ofSeconds(29));
		this.dispatcher.runOnce();
		assertThat(RECEIVER.requests(hook)).hasSize(1);
		CLOCK.advance(Duration.ofSeconds(1));
		this.dispatcher.runOnce();
		List<FakeReceiver.Request> requests = RECEIVER.requests(hook);
		assertThat(requests).hasSize(2);
		assertThat(requests.get(1).header("Cronbell-Delivery")).isEqualTo(requests.get(0).header("Cronbell-Delivery"));
		status = exchange(HttpMethod.GET, "/api/v1/channels/" + channel, null, project.key()).getBody();
		assertThat((Object) JsonPath.read(status, "$.lastError")).isNull();
		assertThat((Integer) JsonPath.read(status, "$.pendingDeliveries")).isZero();
		assertThat((String) JsonPath.read(status, "$.lastDeliveryAt")).isEqualTo(CLOCK.instant().toString());
	}

	@Test
	void givesUpAfterOneHour() {
		Project project = newProject();
		String hook = "/hook/" + UUID.randomUUID();
		RECEIVER.always(hook, FakeReceiver.Response.status(503));
		String channel = createChannel(project,
				"{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url(hook) + "\"}}");
		goDown(project);
		Instant raised = CLOCK.instant();
		for (int minute = 0; minute <= 90; minute++) {
			this.dispatcher.runOnce();
			CLOCK.advance(Duration.ofMinutes(1));
		}
		// At 0, 1, 2, 4, 8, 16, 31 and 46 minutes: backoff 30 s doubling, at most 15 min,
		// seen once a minute. The next one would be after the hour.
		assertThat(RECEIVER.requests(hook)).hasSize(8);
		Map<String, Object> row = this.jdbc
			.sql("SELECT status, attempts, last_attempt_at FROM deliveries WHERE channel_id = ?")
			.param(channel)
			.query()
			.singleRow();
		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(8);
		assertThat(((Number) row.get("last_attempt_at")).longValue())
			.isLessThan(raised.plus(Duration.ofHours(1)).toEpochMilli());
		String status = exchange(HttpMethod.GET, "/api/v1/channels/" + channel, null, project.key()).getBody();
		assertThat((String) JsonPath.read(status, "$.lastError"))
			.isEqualTo("Gave up after 8 attempts in 60 min: HTTP 503 from localhost");
		assertThat((Integer) JsonPath.read(status, "$.pendingDeliveries")).isZero();
	}

	@Test
	void clientErrorsAreNotRetried() {
		Project project = newProject();
		String hook = "/hook/" + UUID.randomUUID();
		RECEIVER.always(hook, FakeReceiver.Response.of(410, "gone"));
		String channel = createChannel(project,
				"{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url(hook) + "\"}}");
		goDown(project);
		this.dispatcher.runOnce();
		CLOCK.advance(Duration.ofMinutes(10));
		this.dispatcher.runOnce();
		assertThat(RECEIVER.requests(hook)).hasSize(1);
		String status = exchange(HttpMethod.GET, "/api/v1/channels/" + channel, null, project.key()).getBody();
		assertThat((String) JsonPath.read(status, "$.lastError")).isEqualTo("HTTP 410 from localhost: gone");
	}

	@Test
	void concurrentSendersDeliverOnce() throws Exception {
		Project project = newProject();
		String hook = "/hook/" + UUID.randomUUID();
		createChannel(project, "{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url(hook) + "\"}}");
		String key = goDown(project);
		// Still DOWN: a second failure does not alert again.
		CLOCK.advance(Duration.ofSeconds(1));
		post("/ping/" + key + "/fail");
		try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
			List<Future<Integer>> runs = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				runs.add(executor.submit(() -> this.dispatcher.runOnce()));
			}
			for (Future<Integer> run : runs) {
				run.get();
			}
		}
		this.dispatcher.runOnce();
		assertThat(RECEIVER.requests(hook)).hasSize(1);
	}

	@Test
	void aStuckChannelTimesOutWithoutHoldingUpOthers() {
		Project project = newProject();
		String slow = "/hook/" + UUID.randomUUID();
		RECEIVER.always(slow, new FakeReceiver.Response(200, "", Map.of(), Duration.ofSeconds(3)));
		String slowChannel = createChannel(project,
				"{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url(slow) + "\"}}");
		String fast = "/hook/" + UUID.randomUUID();
		createChannel(project, "{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url(fast) + "\"}}");
		goDown(project);
		this.dispatcher.runOnce();
		assertThat(RECEIVER.requests(fast)).hasSize(1);
		String status = exchange(HttpMethod.GET, "/api/v1/channels/" + slowChannel, null, project.key()).getBody();
		assertThat((String) JsonPath.read(status, "$.lastError"))
			.isEqualTo("Timed out after 1 s waiting for localhost");
		assertThat((Integer) JsonPath.read(status, "$.pendingDeliveries")).isEqualTo(1);
	}

	@Test
	void channelsApiMasksSecrets() {
		Project project = newProject();
		String token = fakeBotToken();
		ResponseEntity<String> created = exchange(HttpMethod.POST, "/api/v1/channels",
				"{\"type\":\"telegram\",\"name\":\"Ops\",\"config\":{\"botToken\":\"" + token
						+ "\",\"chatId\":\"@cronbell_ops\"}}",
				project.key());
		assertThat(created.getStatusCode().value()).isEqualTo(201);
		String id = JsonPath.read(created.getBody(), "$.id");
		assertThat(created.getHeaders().getLocation())
			.hasToString("http://localhost:" + this.port + "/api/v1/channels/" + id);
		assertThat((String) JsonPath.read(created.getBody(), "$.type")).isEqualTo("telegram");
		assertThat((String) JsonPath.read(created.getBody(), "$.name")).isEqualTo("Ops");
		assertThat((String) JsonPath.read(created.getBody(), "$.config.botToken"))
			.isEqualTo("12…" + token.substring(token.length() - 2));
		assertThat((String) JsonPath.read(created.getBody(), "$.config.chatId")).isEqualTo("@cronbell_ops");
		String hookUrl = RECEIVER.url("/hook/private-token-path");
		String hook = createChannel(project, "{\"type\":\"webhook\",\"config\":{\"url\":\"" + hookUrl
				+ "\",\"secret\":\"" + WEBHOOK_SECRET + "\"}}");

		String list = exchange(HttpMethod.GET, "/api/v1/channels", null, project.key()).getBody();
		assertThat(list).doesNotContain(token).doesNotContain(WEBHOOK_SECRET).doesNotContain("private-token-path");
		assertThat((List<String>) JsonPath.read(list, "$[*].config.url")).containsExactly(RECEIVER.url("/") + "…");

		// Round-trip what was read: the secrets are kept.
		String read = exchange(HttpMethod.GET, "/api/v1/channels/" + hook, null, project.key()).getBody();
		String maskedUrl = JsonPath.read(read, "$.config.url");
		String maskedSecret = JsonPath.read(read, "$.config.secret");
		ResponseEntity<String> updated = exchange(HttpMethod.PUT, "/api/v1/channels/" + hook,
				"{\"name\":\"Renamed\",\"config\":{\"url\":\"" + maskedUrl + "\",\"secret\":\"" + maskedSecret + "\"}}",
				project.key());
		assertThat(updated.getStatusCode().value()).as(updated.getBody()).isEqualTo(200);
		assertThat((String) JsonPath.read(updated.getBody(), "$.name")).isEqualTo("Renamed");
		Channel stored = this.channelRepository.findById(hook).orElseThrow();
		assertThat(stored.get("url")).isEqualTo(hookUrl);
		assertThat(stored.get("secret")).isEqualTo(WEBHOOK_SECRET);
		// Leaving a secret out keeps it too; an empty optional secret removes it.
		exchange(HttpMethod.PUT, "/api/v1/channels/" + hook, "{\"config\":{\"secret\":\"\"}}", project.key());
		stored = this.channelRepository.findById(hook).orElseThrow();
		assertThat(stored.get("url")).isEqualTo(hookUrl);
		assertThat(stored.get("secret")).isNull();
		assertThat(stored.name()).isEqualTo("webhook");

		// Other projects see nothing.
		Project other = newProject();
		assertThat(exchange(HttpMethod.GET, "/api/v1/channels/" + id, null, other.key()).getStatusCode().value())
			.isEqualTo(404);
		assertThat(exchange(HttpMethod.DELETE, "/api/v1/channels/" + id, null, other.key()).getStatusCode().value())
			.isEqualTo(404);
		assertThat(exchange(HttpMethod.DELETE, "/api/v1/channels/" + id, null, project.key()).getStatusCode().value())
			.isEqualTo(204);
		assertThat(exchange(HttpMethod.GET, "/api/v1/channels/" + id, null, project.key()).getStatusCode().value())
			.isEqualTo(404);
		assertThat(exchange(HttpMethod.GET, "/api/v1/channels", null, null).getStatusCode().value()).isEqualTo(401);
	}

	@Test
	void rejectsInvalidChannels() {
		Project project = newProject();
		Map<String, String> invalid = Map.of("{\"type\":\"sms\",\"config\":{}}", "type must be one of",
				"{\"type\":\"slack\",\"config\":{\"webhookUrl\":\"http://hooks.slack.com/x\"}}", "an https:// URL",
				"{\"type\":\"webhook\",\"config\":{\"url\":\"ftp://example.com/x\"}}", "http:// or https://",
				"{\"type\":\"webhook\",\"config\":{}}", "config.url is required",
				"{\"type\":\"telegram\",\"config\":{\"botToken\":\"" + fakeBotToken() + "\",\"chatId\":\"me\"}}",
				"config.chatId", "{\"type\":\"telegram\",\"config\":{\"botToken\":\"nope\",\"chatId\":\"1\"}}",
				"config.botToken", "{\"type\":\"email\",\"config\":{\"address\":\"not an address\"}}", "config.address",
				"{\"type\":\"email\",\"config\":{\"address\":\"a@example.com\",\"cc\":\"b@example.com\"}}",
				"unknown config field");
		invalid.forEach((json, detail) -> {
			ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/v1/channels", json, project.key());
			assertThat(response.getStatusCode().value()).as(json).isEqualTo(400);
			assertThat((String) JsonPath.read(response.getBody(), "$.detail")).as(json).contains(detail);
		});
		String id = createChannel(project, "{\"type\":\"email\",\"config\":{\"address\":\"a@example.com\"}}");
		ResponseEntity<String> retype = exchange(HttpMethod.PUT, "/api/v1/channels/" + id,
				"{\"type\":\"slack\",\"config\":{\"webhookUrl\":\"https://hooks.slack.com/x\"}}", project.key());
		assertThat(retype.getStatusCode().value()).isEqualTo(400);
	}

	@Test
	void testMessageIsSentAtOnceAndRateLimited() {
		Project project = newProject();
		String token = fakeBotToken();
		String id = createChannel(project,
				"{\"type\":\"telegram\",\"config\":{\"botToken\":\"" + token + "\",\"chatId\":\"42\"}}");
		ResponseEntity<String> sent = exchange(HttpMethod.POST, "/api/v1/channels/" + id + "/test", null,
				project.key());
		assertThat(sent.getStatusCode().value()).isEqualTo(200);
		assertThat((Boolean) JsonPath.read(sent.getBody(), "$.delivered")).isTrue();
		String text = JsonPath.read(RECEIVER.requests("/tg/bot" + token + "/sendMessage").getFirst().body(), "$.text");
		assertThat(text).startsWith("<b>[Cronbell] Test message</b>");
		assertThat(exchange(HttpMethod.POST, "/api/v1/channels/" + id + "/test", null, project.key()).getStatusCode()
			.value()).isEqualTo(429);
		CLOCK.advance(Duration.ofSeconds(10));
		assertThat(exchange(HttpMethod.POST, "/api/v1/channels/" + id + "/test", null, project.key()).getStatusCode()
			.value()).isEqualTo(200);

		String badToken = fakeBotToken();
		RECEIVER.always("/tg/bot" + badToken + "/sendMessage",
				FakeReceiver.Response.of(401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}"));
		String bad = createChannel(project,
				"{\"type\":\"telegram\",\"config\":{\"botToken\":\"" + badToken + "\",\"chatId\":\"42\"}}");
		ResponseEntity<String> failed = exchange(HttpMethod.POST, "/api/v1/channels/" + bad + "/test", null,
				project.key());
		assertThat(failed.getStatusCode().value()).isEqualTo(502);
		assertThat((String) JsonPath.read(failed.getBody(), "$.detail"))
			.isEqualTo("HTTP 401 from Telegram: check the bot token");
		assertThat(failed.getBody()).doesNotContain(badToken);
	}

	@Test
	void discordGetsAnEmbedAndIsRetriedWhenRateLimited() {
		Project project = newProject();
		String path = "/discord/" + UUID.randomUUID();
		RECEIVER.script(path,
				FakeReceiver.Response.of(429, "{\"message\":\"You are being rate limited.\",\"retry_after\":45.5}"),
				FakeReceiver.Response.status(204));
		String channel = insertWebhookUrlChannel(project, ChannelType.DISCORD, path);
		goDown(project);
		this.dispatcher.runOnce();
		String status = exchange(HttpMethod.GET, "/api/v1/channels/" + channel, null, project.key()).getBody();
		assertThat((String) JsonPath.read(status, "$.lastError"))
			.isEqualTo("HTTP 429 from Discord: You are being rate limited.");

		// Discord's retry_after is longer than the first 30 s backoff, so it wins.
		CLOCK.advance(Duration.ofSeconds(45));
		this.dispatcher.runOnce();
		assertThat(RECEIVER.requests(path)).hasSize(1);
		CLOCK.advance(Duration.ofSeconds(1));
		this.dispatcher.runOnce();
		assertThat(RECEIVER.requests(path)).hasSize(2);
		String body = RECEIVER.requests(path).getLast().body();
		assertThat((String) JsonPath.read(body, "$.username")).isEqualTo("Cronbell");
		assertThat((List<Object>) JsonPath.read(body, "$.allowed_mentions.parse")).isEmpty();
		assertThat((String) JsonPath.read(body, "$.embeds[0].title")).endsWith(" is DOWN: run failed");
		assertThat((Integer) JsonPath.read(body, "$.embeds[0].color")).isEqualTo(0xD92D20);
		assertThat((String) JsonPath.read(body, "$.embeds[0].url")).startsWith("https://cronbell.example.com/checks/");
		status = exchange(HttpMethod.GET, "/api/v1/channels/" + channel, null, project.key()).getBody();
		assertThat((Object) JsonPath.read(status, "$.lastError")).isNull();
	}

	@Test
	void discordWaitsForTheLongerOfRetryAfterAndItsBody() {
		Project project = newProject();
		String path = "/discord/" + UUID.randomUUID();
		RECEIVER.script(path,
				new FakeReceiver.Response(429, "{\"message\":\"You are being rate limited.\",\"retry_after\":1}",
						Map.of("Retry-After", "120"), Duration.ZERO),
				FakeReceiver.Response.status(204));
		insertWebhookUrlChannel(project, ChannelType.DISCORD, path);
		goDown(project);
		this.dispatcher.runOnce();
		assertThat(RECEIVER.requests(path)).hasSize(1);

		// Neither the body's 1 s nor the first 30 s backoff: the header's 120 s.
		CLOCK.advance(Duration.ofSeconds(119));
		this.dispatcher.runOnce();
		assertThat(RECEIVER.requests(path)).hasSize(1);
		CLOCK.advance(Duration.ofSeconds(1));
		this.dispatcher.runOnce();
		assertThat(RECEIVER.requests(path)).hasSize(2);
	}

	@Test
	void unknownDiscordWebhookIsNotRetried() {
		Project project = newProject();
		String path = "/discord/" + UUID.randomUUID();
		RECEIVER.always(path, FakeReceiver.Response.of(404, "{\"message\":\"Unknown Webhook\",\"code\":10015}"));
		String channel = insertWebhookUrlChannel(project, ChannelType.DISCORD, path);
		goDown(project);
		this.dispatcher.runOnce();
		CLOCK.advance(Duration.ofMinutes(10));
		this.dispatcher.runOnce();
		assertThat(RECEIVER.requests(path)).hasSize(1);
		String status = exchange(HttpMethod.GET, "/api/v1/channels/" + channel, null, project.key()).getBody();
		assertThat((String) JsonPath.read(status, "$.lastError"))
			.isEqualTo("HTTP 404 from Discord: the webhook was deleted or its URL is wrong");
	}

	@Test
	void ntfyGetsTitlePriorityTagsAndClickWithTheAccessToken() {
		Project project = newProject();
		String server = "/ntfy/" + UUID.randomUUID();
		String token = "tk_" + UUID.randomUUID().toString().replace("-", "");
		ResponseEntity<String> created = exchange(HttpMethod.POST, "/api/v1/channels", "{\"type\":\"ntfy\",\"config\":"
				+ "{\"serverUrl\":\"" + RECEIVER.url(server) + "/\",\"topic\":\"ops\",\"token\":\"" + token + "\"}}",
				project.key());
		assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
		assertThat(created.getBody()).doesNotContain(token);
		assertThat((String) JsonPath.read(created.getBody(), "$.config.serverUrl")).isEqualTo(RECEIVER.url(server));
		String slug = uniqueSlug();
		String key = createCheck(project, slug, "{\"cron\":\"0 * * * *\"}");
		goDown(key);
		this.dispatcher.runOnce();

		FakeReceiver.Request down = RECEIVER.requests(server).getFirst();
		assertThat(down.header("Authorization")).isEqualTo("Bearer " + token);
		assertThat((String) JsonPath.read(down.body(), "$.topic")).isEqualTo("ops");
		assertThat((String) JsonPath.read(down.body(), "$.title"))
			.isEqualTo("[Cronbell] " + slug + " is DOWN: run failed");
		assertThat((Integer) JsonPath.read(down.body(), "$.priority")).isEqualTo(4);
		assertThat((List<String>) JsonPath.read(down.body(), "$.tags")).containsExactly("rotating_light");
		assertThat((String) JsonPath.read(down.body(), "$.click"))
			.isEqualTo("https://cronbell.example.com/checks/" + slug);

		CLOCK.advance(Duration.ofMinutes(1));
		get("/ping/" + key);
		this.dispatcher.runOnce();
		FakeReceiver.Request up = RECEIVER.requests(server).getLast();
		assertThat((Integer) JsonPath.read(up.body(), "$.priority")).isEqualTo(3);
		assertThat((List<String>) JsonPath.read(up.body(), "$.tags")).containsExactly("white_check_mark");
	}

	@Test
	void ntfyRefusalsSayWhatToFix() {
		Project project = newProject();
		String server = "/ntfy/" + UUID.randomUUID();
		RECEIVER.always(server, FakeReceiver.Response.of(403,
				"{\"code\":40301,\"http\":403,\"error\":\"forbidden\",\"link\":\"https://ntfy.sh/docs/publish/#authentication\"}"));
		String open = createChannel(project,
				"{\"type\":\"ntfy\",\"config\":{\"serverUrl\":\"" + RECEIVER.url(server) + "\",\"topic\":\"ops\"}}");
		ResponseEntity<String> failed = exchange(HttpMethod.POST, "/api/v1/channels/" + open + "/test", null,
				project.key());
		assertThat(failed.getStatusCode().value()).isEqualTo(502);
		assertThat((String) JsonPath.read(failed.getBody(), "$.detail"))
			.isEqualTo("HTTP 403 from ntfy: the topic needs an access token");
	}

	@Test
	void anErrorBodyEchoingASecretNeverKeepsAPieceOfIt() {
		Project project = newProject();
		// The token starts at character 193 of the body and the error keeps 200.
		String server = "/ntfy/" + UUID.randomUUID();
		String token = "tk_" + UUID.randomUUID().toString().replace("-", "");
		RECEIVER.always(server, FakeReceiver.Response.of(500, "a".repeat(185) + " token=" + token + " was refused"));
		String ntfy = createChannel(project, "{\"type\":\"ntfy\",\"config\":{\"serverUrl\":\"" + RECEIVER.url(server)
				+ "\",\"topic\":\"ops\",\"token\":\"" + token + "\"}}");
		// Discord's webhook path, without the host, from character 182.
		String path = "/discord/" + UUID.randomUUID();
		RECEIVER.always(path, FakeReceiver.Response.of(502, "b".repeat(180) + " " + path + " is unavailable"));
		String discord = insertWebhookUrlChannel(project, ChannelType.DISCORD, path);
		goDown(project);
		this.dispatcher.runOnce();

		String ntfyError = JsonPath
			.read(exchange(HttpMethod.GET, "/api/v1/channels/" + ntfy, null, project.key()).getBody(), "$.lastError");
		assertThat(ntfyError).startsWith("HTTP 500 from ntfy: aaa").endsWith(" token=**** was…");
		assertThat(ntfyError).doesNotContain(token.substring(0, 8));
		String discordError = JsonPath.read(
				exchange(HttpMethod.GET, "/api/v1/channels/" + discord, null, project.key()).getBody(), "$.lastError");
		assertThat(discordError).startsWith("HTTP 502 from Discord: bbb").contains(" **** is");
		assertThat(discordError).doesNotContain(path.substring(0, 18));
	}

	@Test
	void unreachableChannelsGetAPlainReason() {
		Project project = newProject();
		String channel = createChannel(project,
				"{\"type\":\"webhook\",\"config\":{\"url\":\"https://hooks.example.invalid/secret-path\"}}");
		ResponseEntity<String> failed = exchange(HttpMethod.POST, "/api/v1/channels/" + channel + "/test", null,
				project.key());
		assertThat(failed.getStatusCode().value()).isEqualTo(502);
		assertThat((String) JsonPath.read(failed.getBody(), "$.detail"))
			.isEqualTo("Could not resolve hooks.example.invalid");
	}

	// --- Alert routing ---

	@Test
	void aCheckAlertsOnlyThePickedChannels() {
		Project project = newProject();
		String picked = "/hook/" + UUID.randomUUID();
		String pickedId = createChannel(project,
				"{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url(picked) + "\"}}");
		String other = "/hook/" + UUID.randomUUID();
		String otherId = createChannel(project,
				"{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url(other) + "\"}}");
		String slug = uniqueSlug();
		ResponseEntity<String> created = exchange(HttpMethod.PUT, "/api/v1/checks/" + slug,
				"{\"cron\":\"0 * * * *\",\"channels\":[\"" + pickedId + "\",\"" + pickedId + "\"]}", project.key());
		assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
		assertThat((List<String>) JsonPath.read(created.getBody(), "$.channels")).containsExactly(pickedId);
		goDown((String) JsonPath.read(created.getBody(), "$.pingKey"));
		this.dispatcher.runOnce();
		assertThat(RECEIVER.requests(picked)).hasSize(1);
		assertThat(RECEIVER.requests(other)).isEmpty();

		// The check's history shows the alert where it went, and only there.
		String events = exchange(HttpMethod.GET, "/api/v1/checks/" + slug + "/events", null, project.key()).getBody();
		String alerts = exchange(HttpMethod.GET, "/api/v1/checks/" + slug + "/alerts", null, project.key()).getBody();
		assertThat((List<String>) JsonPath.read(alerts, "$[*].channelId")).containsExactly(pickedId);
		assertThat((List<String>) JsonPath.read(alerts, "$[*].eventId"))
			.containsExactly((String) JsonPath.read(events, "$[0].id"));
		assertThat((List<String>) JsonPath.read(alerts, "$[*].status")).containsExactly("DELIVERED");

		String channels = exchange(HttpMethod.GET, "/api/v1/channels", null, project.key()).getBody();
		assertThat((List<Integer>) JsonPath.read(channels, "$[?(@.id == '" + pickedId + "')].pickedBy"))
			.containsExactly(1);
		assertThat((List<Integer>) JsonPath.read(channels, "$[?(@.id == '" + otherId + "')].pickedBy"))
			.containsExactly(0);
		assertThat((List<String>) field(project.key(), slug, "channels")).containsExactly(pickedId);
	}

	@Test
	void leavingChannelsOutKeepsTheSelectionAndNullResetsIt() {
		Project project = newProject();
		String channel = createChannel(project, "{\"type\":\"email\",\"config\":{\"address\":\"a@example.com\"}}");
		String slug = uniqueSlug();
		createCheck(project, slug, "{\"cron\":\"0 * * * *\"}");
		assertThat(field(project.key(), slug, "channels")).isNull();
		String path = "/api/v1/checks/" + slug;
		exchange(HttpMethod.PUT, path, "{\"cron\":\"0 * * * *\",\"channels\":[\"" + channel + "\"]}", project.key());

		// The starter registers its checks at start-up without channels.
		ResponseEntity<String> registered = exchange(HttpMethod.PUT, path, "{\"cron\":\"5 * * * *\"}", project.key());
		assertThat((List<String>) JsonPath.read(registered.getBody(), "$.channels")).containsExactly(channel);

		ResponseEntity<String> reset = exchange(HttpMethod.PUT, path, "{\"cron\":\"5 * * * *\",\"channels\":null}",
				project.key());
		assertThat((Object) JsonPath.read(reset.getBody(), "$.channels")).isNull();
		exchange(HttpMethod.PUT, path, "{\"cron\":\"5 * * * *\",\"channels\":[\"" + channel + "\"]}", project.key());
		ResponseEntity<String> empty = exchange(HttpMethod.PUT, path, "{\"cron\":\"5 * * * *\",\"channels\":[]}",
				project.key());
		assertThat((Object) JsonPath.read(empty.getBody(), "$.channels")).isNull();
	}

	@Test
	void aCheckCannotPickAnotherProjectsChannel() {
		Project project = newProject();
		Project other = newProject();
		String foreign = createChannel(other, "{\"type\":\"email\",\"config\":{\"address\":\"a@example.com\"}}");
		String slug = uniqueSlug();
		Map<String, String> invalid = Map.of("[\"" + foreign + "\"]",
				"channels: no channel with id " + foreign + " in this project", "[\"nope\"]",
				"channels: no channel with id nope in this project", "\"" + foreign + "\"",
				"channels must be a list of channel ids", "[1]", "channels must be a list of channel ids");
		invalid.forEach((channels, detail) -> {
			ResponseEntity<String> response = exchange(HttpMethod.PUT, "/api/v1/checks/" + slug,
					"{\"cron\":\"0 * * * *\",\"channels\":" + channels + "}", project.key());
			assertThat(response.getStatusCode().value()).as(channels).isEqualTo(400);
			assertThat((String) JsonPath.read(response.getBody(), "$.detail")).as(channels).contains(detail);
		});
		assertThat(exchange(HttpMethod.GET, "/api/v1/checks/" + slug, null, project.key()).getStatusCode().value())
			.isEqualTo(404);
	}

	@Test
	void deletingTheLastPickedChannelFallsBackToEveryChannel() {
		Project project = newProject();
		String picked = createChannel(project,
				"{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url("/hook/" + UUID.randomUUID()) + "\"}}");
		String rest = "/hook/" + UUID.randomUUID();
		createChannel(project, "{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url(rest) + "\"}}");
		String slug = uniqueSlug();
		String key = createCheck(project, slug, "{\"cron\":\"0 * * * *\",\"channels\":[\"" + picked + "\"]}");
		assertThat(
				exchange(HttpMethod.DELETE, "/api/v1/channels/" + picked, null, project.key()).getStatusCode().value())
			.isEqualTo(204);
		assertThat(field(project.key(), slug, "channels")).isNull();
		goDown(key);
		this.dispatcher.runOnce();
		assertThat(RECEIVER.requests(rest)).hasSize(1);
	}

	// --- Accounts and sessions ---

	static final String ADMIN_EMAIL = "admin@example.com";

	static final String ADMIN_PASSWORD = "correct horse battery";

	@Test
	void setupRunsOnceEvenWhenRacing() throws Exception {
		removeAccounts();
		Browser first = new Browser(this.port);
		assertThat(json(first.get("/api/v1/setup"), "$.required", Boolean.class)).isTrue();
		assertThat(first.cookie(Browser.CSRF_COOKIE)).isNotNull();
		// Without the CSRF header the cookie request is refused.
		assertThat(
				first.send("POST", "/api/v1/setup", credentials("a@example.com", ADMIN_PASSWORD), false).statusCode())
			.isEqualTo(403);
		assertThat(first.call("POST", "/api/v1/setup", credentials("not-an-email", ADMIN_PASSWORD)).statusCode())
			.isEqualTo(400);
		assertThat(first.call("POST", "/api/v1/setup", credentials("a@example.com", "short")).statusCode())
			.isEqualTo(400);

		List<Browser> browsers = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			Browser browser = new Browser(this.port);
			browser.get("/api/v1/setup");
			browsers.add(browser);
		}
		List<Integer> statuses = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
			List<Future<Integer>> calls = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				Browser browser = browsers.get(i);
				String email = "admin" + i + "@example.com";
				calls.add(executor.submit(
						() -> browser.call("POST", "/api/v1/setup", credentials(email, ADMIN_PASSWORD)).statusCode()));
			}
			for (Future<Integer> call : calls) {
				statuses.add(call.get());
			}
		}
		assertThat(statuses).containsOnly(201, 409).filteredOn((status) -> status == 201).hasSize(1);
		Browser winner = browsers.get(statuses.indexOf(201));
		HttpResponse<String> session = winner.get("/api/v1/session");
		assertThat(session.statusCode()).isEqualTo(200);
		assertThat(json(session, "$.account.email", String.class))
			.isEqualTo("admin" + statuses.indexOf(201) + "@example.com");
		assertThat(json(session, "$.project.name", String.class)).isEqualTo("Default");
		assertThat(json(session, "$.pingBaseUrl", String.class)).isEqualTo("http://localhost:" + this.port + "/ping/");
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM accounts").query(Long.class).single()).isEqualTo(1);
		assertThat(json(first.get("/api/v1/setup"), "$.required", Boolean.class)).isFalse();
		HttpResponse<String> again = first.call("POST", "/api/v1/setup",
				credentials("late@example.com", ADMIN_PASSWORD));
		assertThat(again.statusCode()).isEqualTo(409);
		assertThat(json(again, "$.detail", String.class)).contains("already done");
	}

	@Test
	void adminAccountFromTheEnvironmentOnFirstStart() {
		removeAccounts();
		new AdminBootstrap(this.accounts, "", "").run(null);
		assertThat(this.accounts.anyAccountExists()).isFalse();
		org.assertj.core.api.Assertions.assertThatIllegalStateException()
			.isThrownBy(() -> new AdminBootstrap(this.accounts, "ops@example.com", "").run(null));
		org.assertj.core.api.Assertions.assertThatIllegalStateException()
			.isThrownBy(() -> new AdminBootstrap(this.accounts, "ops@example.com", "short").run(null));
		new AdminBootstrap(this.accounts, "Ops@Example.com", ADMIN_PASSWORD).run(null);
		new AdminBootstrap(this.accounts, "other@example.com", ADMIN_PASSWORD).run(null);
		assertThat(this.jdbc.sql("SELECT email FROM accounts").query(String.class).list())
			.containsExactly("ops@example.com");
		assertThat(signIn("ops@example.com", ADMIN_PASSWORD).statusCode()).isEqualTo(200);
	}

	@Test
	void signInSetsASecureHttpOnlySessionCookieAndRotatesTheCsrfToken() {
		ensureAdmin();
		Browser browser = new Browser(this.port).header("X-Forwarded-Proto", "https");
		HttpResponse<String> anonymous = browser.get("/api/v1/session");
		assertThat(anonymous.statusCode()).isEqualTo(401);
		String csrfCookie = Browser.setCookie(anonymous, Browser.CSRF_COOKIE);
		assertThat(csrfCookie).contains("SameSite=Lax").contains("Secure").doesNotContain("HttpOnly");
		String tokenBefore = browser.cookie(Browser.CSRF_COOKIE);

		HttpResponse<String> signedIn = browser.call("POST", "/api/v1/session",
				credentials(ADMIN_EMAIL.toUpperCase(), ADMIN_PASSWORD));
		assertThat(signedIn.statusCode()).as(signedIn.body()).isEqualTo(200);
		assertThat(json(signedIn, "$.account.email", String.class)).isEqualTo(ADMIN_EMAIL);
		String sessionCookie = Browser.setCookie(signedIn, "cronbell_session");
		assertThat(sessionCookie).contains("HttpOnly").contains("SameSite=Lax").contains("Secure").contains("Path=/");
		assertThat(browser.cookie(Browser.CSRF_COOKIE)).isNotNull().isNotEqualTo(tokenBefore);

		// Plain HTTP: no Secure flag.
		HttpResponse<String> plain = signIn(ADMIN_EMAIL, ADMIN_PASSWORD);
		assertThat(Browser.setCookie(plain, "cronbell_session")).contains("HttpOnly").doesNotContain("Secure");
	}

	@Test
	void theSessionActsOnTheAdminsProject() {
		Browser browser = signedIn();
		String slug = uniqueSlug();
		createCheck(slug, "{\"cron\":\"0 * * * *\"}");
		HttpResponse<String> checks = browser.get("/api/v1/checks");
		assertThat(checks.statusCode()).isEqualTo(200);
		assertThat((List<String>) JsonPath.read(checks.body(), "$[*].slug")).contains(slug);
		HttpResponse<String> created = browser.call("PUT", "/api/v1/checks/" + uniqueSlug(),
				"{\"periodSeconds\":3600}");
		assertThat(created.statusCode()).isEqualTo(201);
	}

	@Test
	void sessionChangesNeedTheCsrfTokenButBearerKeysDoNot() {
		Browser browser = signedIn();
		String slug = uniqueSlug();
		String body = "{\"cron\":\"0 * * * *\"}";
		assertThat(browser.send("PUT", "/api/v1/checks/" + slug, body, false).statusCode()).isEqualTo(403);
		browser.header(Browser.CSRF_HEADER, "forged");
		assertThat(browser.send("PUT", "/api/v1/checks/" + slug, body, false).statusCode()).isEqualTo(403);
		Browser honest = signedIn();
		assertThat(honest.call("PUT", "/api/v1/checks/" + slug, body).statusCode()).isEqualTo(201);
		assertThat(honest.send("POST", "/api/v1/checks/" + slug + "/pause", null, false).statusCode()).isEqualTo(403);
		assertThat(honest.send("DELETE", "/api/v1/checks/" + slug, null, false).statusCode()).isEqualTo(403);
		assertThat(honest.send("DELETE", "/api/v1/session", null, false).statusCode()).isEqualTo(403);
		assertThat(honest.call("POST", "/api/v1/checks/" + slug + "/pause", null).statusCode()).isEqualTo(200);
		// Bearer requests are stateless: no CSRF, no session, no cookies.
		ResponseEntity<String> bearer = exchange(HttpMethod.PUT, "/api/v1/checks/" + uniqueSlug(), body, API_KEY);
		assertThat(bearer.getStatusCode().value()).isEqualTo(201);
		assertThat(bearer.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
		// A bad bearer key is not rescued by a valid session cookie.
		Browser mixed = signedIn().header(HttpHeaders.AUTHORIZATION, "Bearer cb_wrong_key_wrong_key_wrong");
		assertThat(mixed.get("/api/v1/checks").statusCode()).isEqualTo(401);
		// Pings never need CSRF.
		String key = createCheck(uniqueSlug(), body);
		assertThat(signedIn().send("POST", "/ping/" + key, "done", false).statusCode()).isEqualTo(200);
	}

	@Test
	void wrongPasswordsAreRefusedAndThenRateLimited() {
		ensureAdmin();
		Browser browser = new Browser(this.port);
		browser.get("/api/v1/session");
		HttpResponse<String> unknown = browser.call("POST", "/api/v1/session",
				credentials("nobody@example.com", ADMIN_PASSWORD));
		assertThat(unknown.statusCode()).isEqualTo(401);
		String detail = json(unknown, "$.detail", String.class);
		for (int i = 0; i < 5; i++) {
			HttpResponse<String> wrong = browser.call("POST", "/api/v1/session",
					credentials(ADMIN_EMAIL, "wrong password"));
			assertThat(wrong.statusCode()).isEqualTo(401);
			assertThat(json(wrong, "$.detail", String.class)).isEqualTo(detail);
		}
		HttpResponse<String> blocked = browser.call("POST", "/api/v1/session",
				credentials(ADMIN_EMAIL, ADMIN_PASSWORD));
		assertThat(blocked.statusCode()).isEqualTo(429);
		assertThat(blocked.headers().firstValue("Retry-After")).hasValue("30");
		assertThat(browser.get("/api/v1/session").statusCode()).isEqualTo(401);
		CLOCK.advance(Duration.ofSeconds(29));
		assertThat(browser.call("POST", "/api/v1/session", credentials(ADMIN_EMAIL, ADMIN_PASSWORD)).statusCode())
			.isEqualTo(429);
		CLOCK.advance(Duration.ofSeconds(1));
		// One more failure doubles the wait.
		assertThat(browser.call("POST", "/api/v1/session", credentials(ADMIN_EMAIL, "wrong again")).statusCode())
			.isEqualTo(401);
		HttpResponse<String> longer = browser.call("POST", "/api/v1/session", credentials(ADMIN_EMAIL, ADMIN_PASSWORD));
		assertThat(longer.headers().firstValue("Retry-After")).hasValue("60");
		CLOCK.advance(Duration.ofSeconds(60));
		assertThat(browser.call("POST", "/api/v1/session", credentials(ADMIN_EMAIL, ADMIN_PASSWORD)).statusCode())
			.isEqualTo(200);
		// Success clears the count.
		assertThat(browser.call("POST", "/api/v1/session", credentials(ADMIN_EMAIL, "wrong")).statusCode())
			.isEqualTo(401);
		assertThat(signIn(ADMIN_EMAIL, ADMIN_PASSWORD).statusCode()).isEqualTo(200);
	}

	@Test
	void signOutEndsTheSession() {
		Browser browser = signedIn();
		String oldSession = browser.cookie("cronbell_session");
		assertThat(browser.get("/api/v1/checks").statusCode()).isEqualTo(200);
		HttpResponse<String> out = browser.call("DELETE", "/api/v1/session", null);
		assertThat(out.statusCode()).isEqualTo(204);
		assertThat(browser.cookie("cronbell_session")).isNull();
		assertThat(browser.get("/api/v1/checks").statusCode()).isEqualTo(401);
		assertThat(browser.get("/api/v1/session").statusCode()).isEqualTo(401);
		Browser replay = new Browser(this.port);
		replay.setCookie("cronbell_session", oldSession);
		assertThat(replay.get("/api/v1/checks").statusCode()).isEqualTo(401);
		// Signing out twice is fine.
		assertThat(browser.call("DELETE", "/api/v1/session", null).statusCode()).isEqualTo(204);
	}

	@Test
	void apiKeysAreCreatedShownOnceAndRevoked() {
		Browser browser = signedIn();
		HttpResponse<String> created = browser.call("POST", "/api/v1/keys", "{\"name\":\"CI deploys\"}");
		assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
		String key = json(created, "$.key", String.class);
		String id = json(created, "$.id", String.class);
		assertThat(key).startsWith("cb_").hasSize(46);
		assertThat(json(created, "$.prefix", String.class)).isEqualTo(key.substring(0, 8));
		assertThat(exchange(HttpMethod.GET, "/api/v1/checks", null, key).getStatusCode().value()).isEqualTo(200);

		HttpResponse<String> list = browser.get("/api/v1/keys");
		assertThat(list.body()).doesNotContain(key);
		assertThat((List<String>) JsonPath.read(list.body(), "$[*].name")).contains("bootstrap", "CI deploys");
		// Keys cannot manage keys.
		assertThat(exchange(HttpMethod.GET, "/api/v1/keys", null, key).getStatusCode().value()).isEqualTo(403);
		assertThat(exchange(HttpMethod.POST, "/api/v1/keys", "{}", key).getStatusCode().value()).isEqualTo(403);
		assertThat(exchange(HttpMethod.DELETE, "/api/v1/keys/" + id, null, key).getStatusCode().value()).isEqualTo(403);
		assertThat(new Browser(this.port).get("/api/v1/keys").statusCode()).isEqualTo(401);

		assertThat(browser.call("DELETE", "/api/v1/keys/" + id, null).statusCode()).isEqualTo(204);
		assertThat(exchange(HttpMethod.GET, "/api/v1/checks", null, key).getStatusCode().value()).isEqualTo(401);
		assertThat(browser.call("DELETE", "/api/v1/keys/" + id, null).statusCode()).isEqualTo(404);
		HttpResponse<String> unnamed = browser.call("POST", "/api/v1/keys", null);
		assertThat(json(unnamed, "$.name", String.class)).isEqualTo("API key");
	}

	@Test
	void checkDetailAndPingsWithRunDurations() {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"name\":\"Backup\",\"cron\":\"0 * * * *\"}");
		ResponseEntity<String> detail = exchange(HttpMethod.GET, "/api/v1/checks/" + slug, null, API_KEY);
		assertThat(detail.getStatusCode().value()).isEqualTo(200);
		assertThat((String) JsonPath.read(detail.getBody(), "$.name")).isEqualTo("Backup");
		assertThat(exchange(HttpMethod.GET, "/api/v1/checks/" + uniqueSlug(), null, API_KEY).getStatusCode().value())
			.isEqualTo(404);

		get("/ping/" + key + "/start?rid=a");
		CLOCK.advance(Duration.ofSeconds(2));
		get("/ping/" + key + "/start?rid=b");
		CLOCK.advance(Duration.ofSeconds(3));
		get("/ping/" + key + "/log");
		CLOCK.advance(Duration.ofSeconds(5));
		postBody("/ping/" + key + "/0?rid=a", "done a");
		CLOCK.advance(Duration.ofSeconds(1));
		get("/ping/" + key + "/7");
		String pings = exchange(HttpMethod.GET, "/api/v1/checks/" + slug + "/pings", null, API_KEY).getBody();
		assertThat((List<String>) JsonPath.read(pings, "$[*].kind")).containsExactly("FAIL", "SUCCESS", "LOG", "START",
				"START");
		assertThat((List<Object>) JsonPath.read(pings, "$[*].durationMs")).containsExactly(9000, 10000, null, null,
				null);
		assertThat((List<Object>) JsonPath.read(pings, "$[*].exitCode")).containsExactly(7, 0, null, null, null);
		assertThat((String) JsonPath.read(pings, "$[1].body")).isEqualTo("done a");
		String limited = exchange(HttpMethod.GET, "/api/v1/checks/" + slug + "/pings?limit=2", null, API_KEY).getBody();
		assertThat((List<Object>) JsonPath.read(limited, "$")).hasSize(2);
		assertThat(exchange(HttpMethod.GET, "/api/v1/checks/" + slug + "/pings?limit=0", null, API_KEY).getStatusCode()
			.value()).isEqualTo(400);
		assertThat(exchange(HttpMethod.GET, "/api/v1/checks/" + uniqueSlug() + "/pings", null, API_KEY).getStatusCode()
			.value()).isEqualTo(404);
	}

	@Test
	@SuppressWarnings("unchecked")
	void recentRunsPerCheckOldestFirst() {
		Project project = newProject();
		String busy = uniqueSlug();
		String key = createCheck(project, busy, "{\"cron\":\"0 * * * *\"}");
		String idle = uniqueSlug();
		createCheck(project, idle, "{\"cron\":\"0 * * * *\"}");
		get("/ping/" + key + "/start");
		CLOCK.advance(Duration.ofSeconds(1));
		get("/ping/" + key);
		CLOCK.advance(Duration.ofSeconds(1));
		get("/ping/" + key + "/log");
		CLOCK.advance(Duration.ofSeconds(1));
		get("/ping/" + key + "/3");
		CLOCK.advance(Duration.ofSeconds(1));
		post("/ping/" + key + "/fail");
		CLOCK.advance(Duration.ofSeconds(1));
		get("/ping/" + key);

		String all = exchange(HttpMethod.GET, "/api/v1/runs/recent", null, project.key()).getBody();
		assertThat((List<Boolean>) JsonPath.read(all, "$['" + busy + "'][*].ok")).containsExactly(true, false, false,
				true);
		assertThat((List<Object>) JsonPath.read(all, "$['" + busy + "'][*].exitCode")).containsExactly(null, 3, null,
				null);
		assertThat((List<Object>) JsonPath.read(all, "$['" + idle + "']")).isEmpty();

		String limited = exchange(HttpMethod.GET, "/api/v1/runs/recent?limit=2", null, project.key()).getBody();
		assertThat((List<Boolean>) JsonPath.read(limited, "$['" + busy + "'][*].ok")).containsExactly(false, true);
		assertThat(
				exchange(HttpMethod.GET, "/api/v1/runs/recent?limit=91", null, project.key()).getStatusCode().value())
			.isEqualTo(400);
		// Another project's checks are not listed.
		String other = exchange(HttpMethod.GET, "/api/v1/runs/recent", null, API_KEY).getBody();
		assertThat((Map<String, Object>) JsonPath.read(other, "$")).doesNotContainKey(busy);
	}

	@Test
	@SuppressWarnings("unchecked")
	void dailyRunsAndFailuresInTheCallersTimeZone() {
		Project project = newProject();
		String key = createCheck(project, uniqueSlug(), "{\"cron\":\"0 * * * *\"}");
		// 2026-01-03 23:30 UTC is 2026-01-04 06:30 in Ho Chi Minh City (UTC+7).
		CLOCK.set("2026-01-03T23:30:00Z");
		get("/ping/" + key);
		CLOCK.set("2026-01-04T12:00:00Z");
		get("/ping/" + key + "/start");
		CLOCK.advance(Duration.ofSeconds(1));
		post("/ping/" + key + "/fail");
		CLOCK.set("2026-01-05T10:00:00Z");
		get("/ping/" + key + "/0");
		get("/ping/" + key + "/log");

		String utc = exchange(HttpMethod.GET, "/api/v1/stats/daily?days=3", null, project.key()).getBody();
		assertThat((List<String>) JsonPath.read(utc, "$[*].date")).containsExactly("2026-01-03", "2026-01-04",
				"2026-01-05");
		assertThat((List<Integer>) JsonPath.read(utc, "$[*].runs")).containsExactly(1, 1, 1);
		assertThat((List<Integer>) JsonPath.read(utc, "$[*].failures")).containsExactly(0, 1, 0);

		String hcm = exchange(HttpMethod.GET, "/api/v1/stats/daily?days=3&tz=Asia/Ho_Chi_Minh", null, project.key())
			.getBody();
		assertThat((List<Integer>) JsonPath.read(hcm, "$[*].runs")).containsExactly(0, 2, 1);
		assertThat((List<Integer>) JsonPath.read(hcm, "$[*].failures")).containsExactly(0, 1, 0);

		String fortnight = exchange(HttpMethod.GET, "/api/v1/stats/daily", null, project.key()).getBody();
		assertThat((List<Object>) JsonPath.read(fortnight, "$")).hasSize(14);
		assertThat(exchange(HttpMethod.GET, "/api/v1/stats/daily?tz=Mars/Base", null, project.key()).getStatusCode()
			.value()).isEqualTo(400);
		assertThat(exchange(HttpMethod.GET, "/api/v1/stats/daily?days=0", null, project.key()).getStatusCode().value())
			.isEqualTo(400);
		String empty = exchange(HttpMethod.GET, "/api/v1/stats/daily?days=1", null, newProject().key()).getBody();
		assertThat((List<Integer>) JsonPath.read(empty, "$[*].runs")).containsExactly(0);
	}

	@Test
	@SuppressWarnings("unchecked")
	void schedulePreviewListsTheNextRunsInTheTimeZone() {
		String preview = exchange(HttpMethod.GET,
				"/api/v1/schedules/next?cron=0 3 * * *&timeZone=Asia/Ho_Chi_Minh&count=3", null, API_KEY)
			.getBody();
		assertThat((List<String>) JsonPath.read(preview, "$.runs")).containsExactly("2026-01-05T20:00:00Z",
				"2026-01-06T20:00:00Z", "2026-01-07T20:00:00Z");
		assertThat(exchange(HttpMethod.GET, "/api/v1/schedules/next?cron=every night", null, API_KEY).getStatusCode()
			.value()).isEqualTo(400);
		assertThat(exchange(HttpMethod.GET, "/api/v1/schedules/next?cron=0 0 30 2 *", null, API_KEY).getStatusCode()
			.value()).isEqualTo(400);
		assertThat(exchange(HttpMethod.GET, "/api/v1/schedules/next?cron=@daily&timeZone=Nowhere", null, API_KEY)
			.getStatusCode()
			.value()).isEqualTo(400);
	}

	@Test
	void resumeEndsAPauseWithoutAPing() {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"cron\":\"0 * * * *\",\"graceSeconds\":60}");
		exchange(HttpMethod.POST, "/api/v1/checks/" + slug + "/pause", null, API_KEY);
		ResponseEntity<String> fresh = exchange(HttpMethod.POST, "/api/v1/checks/" + slug + "/resume", null, API_KEY);
		assertThat((String) JsonPath.read(fresh.getBody(), "$.status")).isEqualTo("NEW");

		get("/ping/" + key + "/start");
		exchange(HttpMethod.POST, "/api/v1/checks/" + slug + "/pause", null, API_KEY);
		CLOCK.set("2026-01-07T08:30:00Z");
		ResponseEntity<String> resumed = exchange(HttpMethod.POST, "/api/v1/checks/" + slug + "/resume", null, API_KEY);
		assertThat(resumed.getStatusCode().value()).isEqualTo(200);
		assertThat((String) JsonPath.read(resumed.getBody(), "$.status")).isEqualTo("UP");
		assertThat((String) JsonPath.read(resumed.getBody(), "$.nextDueAt")).isEqualTo("2026-01-07T09:00:00Z");
		this.detector.runOnce();
		assertThat(field(slug, "status")).isEqualTo("UP");
		// Not paused: nothing changes.
		assertThat((String) JsonPath.read(
				exchange(HttpMethod.POST, "/api/v1/checks/" + slug + "/resume", null, API_KEY).getBody(),
				"$.nextDueAt"))
			.isEqualTo("2026-01-07T09:00:00Z");
		assertThat(
				exchange(HttpMethod.POST, "/api/v1/checks/" + uniqueSlug() + "/resume", null, API_KEY).getStatusCode()
					.value())
			.isEqualTo(404);
	}

	@Test
	void deletingACheckRemovesItsPingsAndAlerts() {
		Project project = newProject();
		createChannel(project, "{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url("/hook/deleted") + "\"}}");
		String slug = uniqueSlug();
		String key = createCheck(project, slug, "{\"cron\":\"0 * * * *\"}");
		get("/ping/" + key);
		CLOCK.advance(Duration.ofMinutes(1));
		post("/ping/" + key + "/fail");
		assertThat(exchange(HttpMethod.DELETE, "/api/v1/checks/" + slug, null, API_KEY).getStatusCode().value())
			.as("other project")
			.isEqualTo(404);
		assertThat(exchange(HttpMethod.DELETE, "/api/v1/checks/" + slug, null, project.key()).getStatusCode().value())
			.isEqualTo(204);
		assertThat(exchange(HttpMethod.GET, "/api/v1/checks/" + slug, null, project.key()).getStatusCode().value())
			.isEqualTo(404);
		assertThat(pings(key)).isEmpty();
		assertThat(this.jdbc
			.sql("SELECT COUNT(*) FROM deliveries d JOIN channels c ON c.id = d.channel_id" + " WHERE c.project_id = ?")
			.param(project.id())
			.query(Long.class)
			.single()).isZero();
		assertThat(get("/ping/" + key).getStatusCode().value()).isEqualTo(404);
	}

	// --- Status history ---

	@Test
	@SuppressWarnings("unchecked")
	void everyStatusChangeIsRecordedWithTheAlertsItRaised() {
		Project project = newProject();
		String hook = "/hook/" + UUID.randomUUID();
		createChannel(project,
				"{\"type\":\"webhook\",\"name\":\"Ops hook\",\"config\":{\"url\":\"" + RECEIVER.url(hook) + "\"}}");
		String slug = uniqueSlug();
		String key = createCheck(project, slug, "{\"cron\":\"0 * * * *\",\"graceSeconds\":60}");
		get("/ping/" + key);
		CLOCK.advance(Duration.ofMinutes(1));
		post("/ping/" + key + "/fail");
		get("/ping/" + key + "/log");
		CLOCK.advance(Duration.ofMinutes(1));
		get("/ping/" + key);
		CLOCK.advance(Duration.ofSeconds(1));
		exchange(HttpMethod.POST, "/api/v1/checks/" + slug + "/pause", null, project.key());
		exchange(HttpMethod.POST, "/api/v1/checks/" + slug + "/pause", null, project.key());
		CLOCK.advance(Duration.ofSeconds(1));
		exchange(HttpMethod.POST, "/api/v1/checks/" + slug + "/resume", null, project.key());
		this.dispatcher.runOnce();
		CLOCK.set("2026-01-05T11:02:00Z");
		this.detector.runOnce();
		this.dispatcher.runOnce();

		String events = exchange(HttpMethod.GET, "/api/v1/checks/" + slug + "/events", null, project.key()).getBody();
		assertThat((List<String>) JsonPath.read(events, "$[*].kind")).containsExactly("DOWN", "RESUMED", "PAUSED", "UP",
				"DOWN", "UP");
		assertThat((List<Object>) JsonPath.read(events, "$[*].reason")).containsExactly("MISSED", null, null, null,
				"FAILED", null);
		assertThat((String) JsonPath.read(events, "$[0].runAt")).isEqualTo("2026-01-05T11:00:00Z");
		assertThat((String) JsonPath.read(events, "$[0].at")).isEqualTo("2026-01-05T11:02:00Z");
		assertThat((String) JsonPath.read(events, "$[4].at")).isEqualTo("2026-01-05T10:01:00Z");
		assertThat((List<String>) JsonPath.read(events, "$[*].slug")).containsOnly(slug);

		String alerts = exchange(HttpMethod.GET, "/api/v1/checks/" + slug + "/alerts", null, project.key()).getBody();
		// Going UP the first time alerts nobody.
		assertThat((List<String>) JsonPath.read(alerts, "$[*].eventId")).containsExactly(
				JsonPath.read(events, "$[0].id"), JsonPath.read(events, "$[3].id"), JsonPath.read(events, "$[4].id"));
		assertThat((List<String>) JsonPath.read(alerts, "$[*].channelName")).containsOnly("Ops hook");
		assertThat((List<String>) JsonPath.read(alerts, "$[*].channelType")).containsOnly("webhook");
		assertThat((List<String>) JsonPath.read(alerts, "$[*].status")).containsOnly("DELIVERED");

		String recent = exchange(HttpMethod.GET, "/api/v1/events?limit=2", null, project.key()).getBody();
		assertThat((List<String>) JsonPath.read(recent, "$[*].kind")).containsExactly("DOWN", "RESUMED");
		for (String path : List.of("/api/v1/events?limit=0", "/api/v1/events?limit=201",
				"/api/v1/checks/" + slug + "/events?limit=0", "/api/v1/checks/" + slug + "/alerts?limit=201")) {
			assertThat(exchange(HttpMethod.GET, path, null, project.key()).getStatusCode().value()).as(path)
				.isEqualTo(400);
		}
		for (String path : List.of("/api/v1/checks/" + uniqueSlug() + "/events",
				"/api/v1/checks/" + uniqueSlug() + "/alerts")) {
			assertThat(exchange(HttpMethod.GET, path, null, project.key()).getStatusCode().value()).as(path)
				.isEqualTo(404);
		}
	}

	@Test
	void theCheckHasItsUptimeAndAverageRunDuration() {
		Project project = newProject();
		String slug = uniqueSlug();
		String key = createCheck(project, slug, "{\"cron\":\"0 * * * *\"}");
		assertThat(stat(project, slug, "uptime7d")).isNull();
		assertThat(stat(project, slug, "averageDurationMs7d")).isNull();

		get("/ping/" + key + "/start");
		CLOCK.advance(Duration.ofSeconds(10));
		get("/ping/" + key);
		get("/ping/" + key + "/start");
		CLOCK.advance(Duration.ofSeconds(30));
		post("/ping/" + key + "/fail");
		CLOCK.advance(Duration.ofHours(1));
		get("/ping/" + key);
		CLOCK.set("2026-01-06T10:00:00Z");
		assertThat((Double) stat(project, slug, "uptime7d")).isEqualTo(23.0 / 24);
		assertThat((Double) stat(project, slug, "uptime30d")).isEqualTo(23.0 / 24);
		assertThat(stat(project, slug, "averageDurationMs7d")).isEqualTo(20000);
		// Only the detail has them.
		assertThat(field(project.key(), slug, "stats")).isNull();

		// Paused time is left out, and the 7 days start after the failure.
		exchange(HttpMethod.POST, "/api/v1/checks/" + slug + "/pause", null, project.key());
		CLOCK.set("2026-01-07T10:00:00Z");
		exchange(HttpMethod.POST, "/api/v1/checks/" + slug + "/resume", null, project.key());
		CLOCK.set("2026-01-14T10:00:00Z");
		assertThat((Double) stat(project, slug, "uptime7d")).isEqualTo(1.0);
		assertThat((Double) stat(project, slug, "uptime30d")).isEqualTo(191.0 / 192);
		assertThat(stat(project, slug, "averageDurationMs7d")).isNull();
	}

	Object stat(Project project, String slug, String name) {
		return JsonPath.read(exchange(HttpMethod.GET, "/api/v1/checks/" + slug, null, project.key()).getBody(),
				"$.stats." + name);
	}

	@Test
	@SuppressWarnings("unchecked")
	void statusStripTellsHowEachCheckSpentEachDay() {
		Project project = newProject();
		CLOCK.set("2026-01-03T12:00:00Z");
		String flaky = uniqueSlug();
		String flakyKey = createCheck(project, flaky, "{\"cron\":\"0 12 * * *\"}");
		get("/ping/" + flakyKey);
		String missed = uniqueSlug();
		get("/ping/" + createCheck(project, missed, "{\"cron\":\"0 * * * *\"}"));
		CLOCK.set("2026-01-04T00:00:00Z");
		this.detector.runOnce();
		CLOCK.set("2026-01-04T08:00:00Z");
		post("/ping/" + flakyKey + "/fail");
		CLOCK.set("2026-01-04T09:00:00Z");
		get("/ping/" + flakyKey);
		CLOCK.set("2026-01-05T09:00:00Z");
		post("/ping/" + flakyKey + "/fail");
		CLOCK.set("2026-01-05T10:00:00Z");
		String fresh = uniqueSlug();
		createCheck(project, fresh, "{\"cron\":\"0 * * * *\"}");

		String strip = exchange(HttpMethod.GET, "/api/v1/stats/status?days=4", null, project.key()).getBody();
		String days = "$[?(@.slug == '%s')].days[*].";
		assertThat((List<String>) JsonPath.read(strip, days.formatted(flaky) + "date")).containsExactly("2026-01-02",
				"2026-01-03", "2026-01-04", "2026-01-05");
		assertThat((List<String>) JsonPath.read(strip, days.formatted(flaky) + "state")).containsExactly("NONE", "OK",
				"ISSUES", "DOWN");
		assertThat((List<Integer>) JsonPath.read(strip, days.formatted(flaky) + "runs")).containsExactly(0, 1, 2, 1);
		assertThat((List<Integer>) JsonPath.read(strip, days.formatted(flaky) + "failures")).containsExactly(0, 0, 1,
				1);
		assertThat((List<Number>) JsonPath.read(strip, days.formatted(flaky) + "downMs")).extracting(Number::longValue)
			.containsExactly(0L, 0L, 3_600_000L, 3_600_000L);
		assertThat((List<String>) JsonPath.read(strip, days.formatted(missed) + "state")).containsExactly("NONE", "OK",
				"DOWN", "DOWN");
		assertThat((List<Integer>) JsonPath.read(strip, days.formatted(missed) + "missed")).containsExactly(0, 0, 1, 0);
		assertThat((List<String>) JsonPath.read(strip, days.formatted(fresh) + "state")).containsOnly("NONE");

		assertThat((List<Object>) JsonPath
			.read(exchange(HttpMethod.GET, "/api/v1/stats/status", null, project.key()).getBody(), "$[0].days"))
			.hasSize(30);
		for (String query : List.of("days=0", "days=91", "tz=Mars/Base")) {
			assertThat(exchange(HttpMethod.GET, "/api/v1/stats/status?" + query, null, project.key()).getStatusCode()
				.value()).as(query).isEqualTo(400);
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void pruningKeepsTheLatestStatusEventOfACheck() {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"cron\":\"0 * * * *\"}");
		get("/ping/" + key);
		CLOCK.advance(Duration.ofMinutes(1));
		post("/ping/" + key + "/fail");
		CLOCK.advance(Duration.ofMinutes(1));
		get("/ping/" + key);
		CLOCK.advance(Duration.ofDays(100));
		post("/ping/" + key + "/fail");
		assertThat(this.pruner.runOnce().events()).isGreaterThanOrEqualTo(2);
		String events = exchange(HttpMethod.GET, "/api/v1/checks/" + slug + "/events", null, API_KEY).getBody();
		assertThat((List<String>) JsonPath.read(events, "$[*].kind")).containsExactly("DOWN", "UP");
		assertThat((String) JsonPath.read(events, "$[1].at")).isEqualTo("2026-01-05T10:02:00Z");
	}

	@Test
	@SuppressWarnings("unchecked")
	void changesInTheSameMillisecondKeepTheirOrder() {
		Project project = newProject();
		String slug = uniqueSlug();
		String key = createCheck(project, slug, "{\"cron\":\"0 * * * *\"}");
		get("/ping/" + key);
		CLOCK.advance(Duration.ofMinutes(1));
		// DOWN, then UP again, in the same millisecond.
		post("/ping/" + key + "/fail");
		get("/ping/" + key);
		// UUIDv7 ids are random within a millisecond: give the later event the smaller
		// id.
		List<String> ids = this.jdbc.sql("""
				SELECT e.id FROM status_events e JOIN checks c ON c.id = e.check_id
				 WHERE c.ping_key = ? AND e.at = ? ORDER BY e.seq""")
			.params(key, CLOCK.instant().toEpochMilli())
			.query(String.class)
			.list();
		assertThat(ids).hasSize(2);
		String low = (ids.get(0).compareTo(ids.get(1)) < 0) ? ids.get(0) : ids.get(1);
		String high = low.equals(ids.get(0)) ? ids.get(1) : ids.get(0);
		String swap = UUID.randomUUID().toString();
		this.jdbc.sql("UPDATE status_events SET id = ? WHERE id = ?").params(swap, ids.get(0)).update();
		this.jdbc.sql("UPDATE status_events SET id = ? WHERE id = ?").params(low, ids.get(1)).update();
		this.jdbc.sql("UPDATE status_events SET id = ? WHERE id = ?").params(high, swap).update();

		String events = exchange(HttpMethod.GET, "/api/v1/checks/" + slug + "/events", null, project.key()).getBody();
		assertThat((List<String>) JsonPath.read(events, "$[*].kind")).containsExactly("UP", "DOWN", "UP");
		assertThat((List<String>) JsonPath
			.read(exchange(HttpMethod.GET, "/api/v1/events?limit=2", null, project.key()).getBody(), "$[*].kind"))
			.containsExactly("UP", "DOWN");
		CLOCK.set("2026-01-06T10:00:00Z");
		assertThat((Double) stat(project, slug, "uptime7d")).isEqualTo(1.0);
		assertThat((List<String>) JsonPath.read(
				exchange(HttpMethod.GET, "/api/v1/stats/status?days=1", null, project.key()).getBody(),
				"$[0].days[*].state"))
			.containsExactly("NONE");

		// Pruning keeps the latest of the two, the one that tells the status.
		CLOCK.advance(Duration.ofDays(100));
		this.pruner.runOnce();
		events = exchange(HttpMethod.GET, "/api/v1/checks/" + slug + "/events", null, project.key()).getBody();
		assertThat((List<String>) JsonPath.read(events, "$[*].kind")).containsExactly("UP");
		assertThat((String) JsonPath.read(events, "$[0].at")).isEqualTo("2026-01-05T10:01:00Z");
	}

	void removeAccounts() {
		this.jdbc.sql("DELETE FROM accounts").update();
	}

	/** Makes sure the admin account exists with the known password. */
	void ensureAdmin() {
		Long admins = this.jdbc.sql("SELECT COUNT(*) FROM accounts WHERE email = ?")
			.param(ADMIN_EMAIL)
			.query(Long.class)
			.single();
		if (admins == 0) {
			removeAccounts();
			this.accounts.createFirstAccount(ADMIN_EMAIL, ADMIN_PASSWORD);
		}
	}

	HttpResponse<String> signIn(String email, String password) {
		Browser browser = new Browser(this.port);
		browser.get("/api/v1/session");
		return browser.call("POST", "/api/v1/session", credentials(email, password));
	}

	/** A browser signed in as the admin. */
	Browser signedIn() {
		ensureAdmin();
		Browser browser = new Browser(this.port);
		browser.get("/api/v1/session");
		HttpResponse<String> response = browser.call("POST", "/api/v1/session",
				credentials(ADMIN_EMAIL, ADMIN_PASSWORD));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return browser;
	}

	static String credentials(String email, String password) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
	}

	static <T> T json(HttpResponse<String> response, String path, Class<T> type) {
		return JsonPath.parse(response.body()).read(path, type);
	}

	// --- History ---

	@Test
	void pruningDeletesOldPingsInBatchesAndKeepsRecentOnes() {
		String key = createCheck(uniqueSlug(), "{\"cron\":\"0 * * * *\"}");
		for (int i = 0; i < 5; i++) {
			get("/ping/" + key);
			CLOCK.advance(Duration.ofMinutes(1));
		}
		CLOCK.advance(Duration.ofDays(95));
		postBody("/ping/" + key + "/log", "recent");
		CLOCK.advance(Duration.ofDays(5));
		get("/ping/" + key);
		HistoryPruner.Result result = this.pruner.runOnce();
		assertThat(result.ran()).isTrue();
		// Batches of 2 (see the test properties): five old pings take three.
		assertThat(result.pings()).isGreaterThanOrEqualTo(5);
		assertThat(pings(key)).extracting((ping) -> ping.get("kind")).containsExactly("SUCCESS", "LOG");
		assertThat(this.pruner.runOnce().pings()).isZero();
	}

	@Test
	void pruningKeepsTheLatestPingAndLogOfACheckForStateAndAlerts() {
		Project project = newProject();
		createChannel(project, "{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url("/history") + "\"}}");
		String slug = uniqueSlug();
		String key = createCheck(project, slug, "{\"cron\":\"0 * * * *\"}");
		postBody("/ping/" + key, "first log");
		CLOCK.advance(Duration.ofMinutes(1));
		postBody("/ping/" + key, "second log");
		CLOCK.advance(Duration.ofMinutes(1));
		get("/ping/" + key);
		Object lastPingAt = field(project.key(), slug, "lastPingAt");
		Object lastSuccessAt = field(project.key(), slug, "lastSuccessAt");
		CLOCK.advance(Duration.ofDays(100));
		assertThat(this.pruner.runOnce().pings()).isGreaterThanOrEqualTo(1);
		assertThat(pings(key)).extracting((ping) -> ping.get("body")).containsExactly(null, "second log");
		assertThat(field(project.key(), slug, "status")).isEqualTo("UP");
		assertThat(field(project.key(), slug, "lastPingAt")).isEqualTo(lastPingAt);
		assertThat(field(project.key(), slug, "lastSuccessAt")).isEqualTo(lastSuccessAt);
		// The missed run's alert still carries the last log.
		this.detector.runOnce();
		assertThat(field(project.key(), slug, "status")).isEqualTo("DOWN");
		String payload = this.jdbc
			.sql("SELECT d.payload FROM deliveries d JOIN checks c ON c.id = d.check_id WHERE c.slug = ?")
			.param(slug)
			.query(String.class)
			.single();
		assertThat(JsonPath.<String>read(payload, "$.log")).isEqualTo("second log");
	}

	@Test
	void pruningKeepsThePingsOfTheOpenRun() {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"cron\":\"0 * * * *\",\"maxRuntimeSeconds\":3600}");
		get("/ping/" + key);
		CLOCK.advance(Duration.ofMinutes(1));
		get("/ping/" + key + "/start");
		CLOCK.advance(Duration.ofMinutes(1));
		get("/ping/" + key + "/log");
		CLOCK.advance(Duration.ofDays(100));
		this.pruner.runOnce();
		assertThat(pings(key)).extracting((ping) -> ping.get("kind")).containsExactly("LOG", "START");
		// The run is still open: finishing it closes it as usual.
		get("/ping/" + key);
		assertThat(field(slug, "status")).isEqualTo("UP");
	}

	@Test
	void pruningDeletesFinishedDeliveriesAndKeepsPendingOnes() {
		Project project = newProject();
		for (int i = 0; i < 4; i++) {
			createChannel(project,
					"{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url("/history/" + i) + "\"}}");
		}
		String key = goDown(project);
		List<String> ids = this.jdbc.sql("""
				SELECT d.id FROM deliveries d JOIN checks c ON c.id = d.check_id
				 WHERE c.ping_key = ? ORDER BY d.id""").param(key).query(String.class).list();
		assertThat(ids).hasSize(4);
		setDelivery(ids.get(0), "DELIVERED", CLOCK.instant());
		setDelivery(ids.get(1), "FAILED", CLOCK.instant());
		setDelivery(ids.get(2), "PENDING", CLOCK.instant());
		setDelivery(ids.get(3), "DELIVERED", CLOCK.instant().plus(Duration.ofDays(95)));
		CLOCK.advance(Duration.ofDays(100));
		assertThat(this.pruner.runOnce().deliveries()).isGreaterThanOrEqualTo(2);
		assertThat(this.jdbc.sql("SELECT id FROM deliveries WHERE id IN (:ids) ORDER BY id")
			.param("ids", ids)
			.query(String.class)
			.list()).containsExactly(ids.get(2), ids.get(3));
	}

	@Test
	void pruningSkipsWhileAnotherInstanceHoldsTheLease() {
		String key = createCheck(uniqueSlug(), "{\"cron\":\"0 * * * *\"}");
		get("/ping/" + key);
		CLOCK.advance(Duration.ofMinutes(1));
		get("/ping/" + key);
		CLOCK.advance(Duration.ofDays(100));
		this.jdbc.sql("UPDATE job_locks SET locked_by = 'other', locked_until = ? WHERE name = 'history-pruning'")
			.param(CLOCK.instant().plus(Duration.ofMinutes(5)).toEpochMilli())
			.update();
		assertThat(this.pruner.runOnce().ran()).isFalse();
		assertThat(pings(key)).hasSize(2);
		// The lease of an instance that died runs out.
		CLOCK.advance(Duration.ofMinutes(5));
		assertThat(this.pruner.runOnce().ran()).isTrue();
		assertThat(pings(key)).hasSize(1);
		assertThat(this.jdbc.sql("SELECT locked_until FROM job_locks WHERE name = 'history-pruning'")
			.query(Long.class)
			.single()).isZero();
	}

	void setDelivery(String id, String status, Instant createdAt) {
		this.jdbc.sql("UPDATE deliveries SET status = ?, created_at = ? WHERE id = ?")
			.params(status, createdAt.toEpochMilli(), id)
			.update();
	}

	// --- Helpers ---

	static String uniqueSlug() {
		return "job-" + UUID.randomUUID().toString().substring(0, 8);
	}

	String createCheck(String slug, String json) {
		ResponseEntity<String> response = put(slug, json);
		assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
		return JsonPath.read(response.getBody(), "$.pingKey");
	}

	ResponseEntity<String> put(String slug, String json) {
		return exchange(HttpMethod.PUT, "/api/v1/checks/" + slug, json, API_KEY);
	}

	Object field(String slug, String name) {
		return field(API_KEY, slug, name);
	}

	Object field(String apiKey, String slug, String name) {
		ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/v1/checks", null, apiKey);
		List<Object> values = JsonPath.read(response.getBody(), "$[?(@.slug == '" + slug + "')]." + name);
		assertThat(values).hasSize(1);
		return values.getFirst();
	}

	ResponseEntity<String> exchange(HttpMethod method, String path, String json, String apiKey) {
		RestClient.RequestBodySpec spec = this.http.method(method).uri(path);
		if (apiKey != null) {
			spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
		}
		if (json != null) {
			spec.contentType(MediaType.APPLICATION_JSON).body(json);
		}
		return spec.retrieve().toEntity(String.class);
	}

	ResponseEntity<String> get(String path) {
		return this.http.get().uri(path).retrieve().toEntity(String.class);
	}

	ResponseEntity<String> post(String path) {
		return this.http.post().uri(path).retrieve().toEntity(String.class);
	}

	/** Pings of a check, most recent first. */
	List<Map<String, Object>> pings(String pingKey) {
		return this.jdbc.sql("""
				SELECT p.kind, p.exit_code, p.rid, p.body, p.remote_addr, p.user_agent
				  FROM pings p JOIN checks c ON c.id = p.check_id
				 WHERE c.ping_key = ? ORDER BY p.received_at DESC, p.id DESC""").param(pingKey).query().listOfRows();
	}

	static final String WEBHOOK_SECRET = "whsec_integration_test_secret";

	record Project(String id, String key) {
	}

	Project newProject() {
		String id = this.projects.createProject("Alerts " + UUID.randomUUID());
		return new Project(id, this.projects.createApiKey(id, "test"));
	}

	/**
	 * Slack and Discord need https, so a channel pointing at the local receiver is stored
	 * directly. Returns its id.
	 */
	String insertWebhookUrlChannel(Project project, ChannelType type, String path) {
		Instant now = CLOCK.instant();
		String id = UUID.randomUUID().toString();
		this.channelRepository
			.insert(new Channel(id, project.id(), type, type.id(), Map.of("webhookUrl", RECEIVER.url(path)), now, now));
		return id;
	}

	String createChannel(Project project, String json) {
		ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/v1/channels", json, project.key());
		assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
		return JsonPath.read(response.getBody(), "$.id");
	}

	String createCheck(Project project, String slug, String json) {
		ResponseEntity<String> response = exchange(HttpMethod.PUT, "/api/v1/checks/" + slug, json, project.key());
		assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
		return JsonPath.read(response.getBody(), "$.pingKey");
	}

	/** Creates a check in the project, pings it, then fails it. Returns its ping key. */
	String goDown(Project project) {
		return goDown(createCheck(project, uniqueSlug(), "{\"cron\":\"0 * * * *\"}"));
	}

	/** Pings the check, then fails it. Returns its ping key. */
	String goDown(String key) {
		get("/ping/" + key);
		CLOCK.advance(Duration.ofMinutes(1));
		post("/ping/" + key + "/fail");
		return key;
	}

	/** Not a real token: digits, a colon and 35 random characters. */
	static String fakeBotToken() {
		return "123456789:" + UUID.randomUUID().toString().replace("-", "") + "abc";
	}

	ResponseEntity<String> postBody(String path, String body) {
		return this.http.post()
			.uri(path)
			.contentType(MediaType.TEXT_PLAIN)
			.body(body)
			.retrieve()
			.toEntity(String.class);
	}

	static List<MimeMessage> mailTo(String address) throws Exception {
		List<MimeMessage> result = new ArrayList<>();
		for (MimeMessage message : MAIL.getReceivedMessages()) {
			if (message.getRecipients(Message.RecipientType.TO)[0].toString().equals(address)) {
				result.add(message);
			}
		}
		return result;
	}

	private static GreenMail startMail() {
		GreenMail mail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
		mail.start();
		return mail;
	}

	/** Collects committed check events. */
	static class EventCollector {

		private final List<CheckEvent> events = new CopyOnWriteArrayList<>();

		@TransactionalEventListener
		void on(CheckEvent event) {
			this.events.add(event);
		}

		List<CheckEvent> forSlug(String slug) {
			return this.events.stream().filter((event) -> event.slug().equals(slug)).toList();
		}

		void clear() {
			this.events.clear();
		}

	}

}
