package com.steelreed.cronbell.server;

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
import java.net.http.HttpResponse;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import com.steelreed.cronbell.server.account.AccountService;
import com.steelreed.cronbell.server.account.AdminBootstrap;
import com.steelreed.cronbell.server.check.CheckEvent;
import com.steelreed.cronbell.server.check.Detector;
import com.steelreed.cronbell.server.notify.Channel;
import com.steelreed.cronbell.server.notify.ChannelRepository;
import com.steelreed.cronbell.server.notify.ChannelType;
import com.steelreed.cronbell.server.notify.DeliveryDispatcher;
import com.steelreed.cronbell.server.notify.WebhookSigner;
import com.steelreed.cronbell.server.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
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
				"server.forward-headers-strategy=native" })
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
		insertSlackChannel(project, slack);
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
		assertThat((String) JsonPath.read(status, "$.lastError")).isEqualTo("HTTP 500: oops");
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
			.isEqualTo("gave up after 8 attempts in 60 min: HTTP 503");
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
		assertThat((String) JsonPath.read(status, "$.lastError")).isEqualTo("HTTP 410: gone");
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
		assertThat((String) JsonPath.read(status, "$.lastError")).isEqualTo("timed out");
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
		assertThat((String) JsonPath.read(failed.getBody(), "$.detail")).isEqualTo("HTTP 401: Unauthorized");
		assertThat(failed.getBody()).doesNotContain(badToken);
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
		ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/v1/checks", null, API_KEY);
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
	 * Slack needs https, so a channel pointing at the local receiver is stored directly.
	 */
	void insertSlackChannel(Project project, String path) {
		Instant now = CLOCK.instant();
		this.channelRepository.insert(new Channel(UUID.randomUUID().toString(), project.id(), ChannelType.SLACK,
				"slack", Map.of("webhookUrl", RECEIVER.url(path)), now, now));
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
		String key = createCheck(project, uniqueSlug(), "{\"cron\":\"0 * * * *\"}");
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
