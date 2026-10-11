package com.steelreed.cronbell.server;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.jayway.jsonpath.JsonPath;
import com.steelreed.cronbell.server.check.CheckEvent;
import com.steelreed.cronbell.server.check.Detector;
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
				"cronbell.bootstrap.api-key=" + AbstractIntegrationTests.API_KEY })
@Import(AbstractIntegrationTests.EventCollector.class)
abstract class AbstractIntegrationTests {

	static final String API_KEY = "cb_integration_test_key_0123456789";

	static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-01-05T10:00:00Z"));

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

	RestClient http;

	@BeforeEach
	void setUp() {
		CLOCK.set("2026-01-05T10:00:00Z");
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
