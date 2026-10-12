package com.steelreed.cronbell.server.checks;

import java.io.IOException;
import java.nio.file.Files;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code CRONBELL_PROJECT_MAX_CHECKS} (DESIGN.md §6, Projects and invites), on SQLite in
 * an application of its own, since the limit is fixed at start-up.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = { "cronbell.scheduling.enabled=false",
		"cronbell.bootstrap.api-key=" + CheckLimitIntegrationTests.KEY, "cronbell.checks.max-per-project=2" })
class CheckLimitIntegrationTests {

	static final String KEY = "cb_check_limit_test_key_0123456789";

	@DynamicPropertySource
	static void dataDir(DynamicPropertyRegistry registry) throws IOException {
		String dir = Files.createTempDirectory("cron-limit-test").toString();
		registry.add("cronbell.data-dir", () -> dir);
	}

	@LocalServerPort
	int port;

	@Test
	void creatingAChecksBeyondTheLimitIs403ButUpdatesWork() {
		assertThat(put("one").getStatusCode().value()).isEqualTo(201);
		assertThat(put("two").getStatusCode().value()).isEqualTo(201);
		ResponseEntity<String> third = put("three");
		assertThat(third.getStatusCode().value()).isEqualTo(403);
		assertThat((String) JsonPath.read(third.getBody(), "$.title")).isEqualTo("Check limit reached");
		assertThat((String) JsonPath.read(third.getBody(), "$.detail"))
			.isEqualTo("this project has reached its limit of 2 checks");
		assertThat((Integer) JsonPath.read(third.getBody(), "$.limit")).isEqualTo(2);
		assertThat(put("two").getStatusCode().value()).isEqualTo(200);
	}

	private ResponseEntity<String> put(String slug) {
		return RestClient.builder()
			.baseUrl("http://localhost:" + this.port)
			.defaultStatusHandler((status) -> true, (request, response) -> {
			})
			.build()
			.put()
			.uri("/api/v1/checks/" + slug)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.body("{\"cron\":\"0 * * * *\"}")
			.retrieve()
			.toEntity(String.class);
	}

}
