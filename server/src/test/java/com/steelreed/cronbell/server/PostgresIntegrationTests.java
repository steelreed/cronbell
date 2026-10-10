package com.steelreed.cronbell.server;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.sql.DataSource;

import com.steelreed.cronbell.server.db.Database;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** The same tests on PostgreSQL, plus multi-instance detector claims. */
@Testcontainers
class PostgresIntegrationTests extends AbstractIntegrationTests {

	@Container
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

	@Autowired
	Database database;

	@Autowired
	DataSource dataSource;

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
	}

	@Test
	void runsOnPostgres() {
		assertThat(this.database.kind()).isEqualTo(Database.Kind.POSTGRESQL);
	}

	@Test
	void detectorSkipsChecksLockedByAnotherInstance() throws Exception {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"cron\":\"0 * * * *\",\"graceSeconds\":60}");
		get("/ping/" + key);
		CLOCK.set("2026-01-05T11:05:00Z");
		// Another instance holds the row while it processes the check.
		try (Connection other = this.dataSource.getConnection()) {
			other.setAutoCommit(false);
			try (PreparedStatement lock = other.prepareStatement("SELECT id FROM checks WHERE ping_key = ? FOR UPDATE")) {
				lock.setString(1, key);
				try (ResultSet rs = lock.executeQuery()) {
					assertThat(rs.next()).isTrue();
				}
			}
			this.detector.runOnce();
			assertThat(field(slug, "status")).isEqualTo("UP");
			other.rollback();
		}
		this.detector.runOnce();
		assertThat(field(slug, "status")).isEqualTo("DOWN");
		assertThat(this.events.forSlug(slug)).hasSize(1);
	}

}
