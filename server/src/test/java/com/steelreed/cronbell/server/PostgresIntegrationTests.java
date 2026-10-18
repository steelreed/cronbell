package com.steelreed.cronbell.server;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.steelreed.cronbell.server.shared.database.Database;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** The same tests on PostgreSQL, plus multi-instance claims and a row-lock race. */
@Testcontainers
class PostgresIntegrationTests extends AbstractProjectsIntegrationTests {

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
	void upgradesABeta1Database() {
		String schema = "upgrade_" + UUID.randomUUID().toString().substring(0, 8);
		String url = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema="
				+ schema;
		upgradeFromBeta1MakesTheSetupAccountInstanceAdmin(
				Flyway.configure()
					.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.schemas(schema)
					.target("4")
					.load(),
				Flyway.configure()
					.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.schemas(schema)
					.load(),
				JdbcClient.create(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword())));
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
			try (PreparedStatement lock = other
				.prepareStatement("SELECT id FROM checks WHERE ping_key = ? FOR UPDATE")) {
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

	@Test
	void recoveryAfterDowntimeWaitsForAChecksPingInsteadOfSkippingIt() throws Exception {
		String slug = uniqueSlug();
		String key = createCheck(slug, "{\"cron\":\"0 * * * *\",\"graceSeconds\":60}");
		get("/ping/" + key);
		CLOCK.set("2026-01-05T10:00:30Z");
		this.detector.runOnce();
		CLOCK.set("2026-01-05T14:00:00Z");
		// A ping (a /log, say) holds the row while the server comes back.
		try (Connection ping = this.dataSource.getConnection();
				ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			ping.setAutoCommit(false);
			lockCheck(ping, key);
			Future<Integer> recovery = executor.submit(this.detector::resumeAfterDowntime);
			awaitLockWait(recovery);
			ping.commit();
			assertThat(recovery.get(10, TimeUnit.SECONDS)).isPositive();
		}
		this.detector.runOnce();
		assertThat(field(slug, "status")).isEqualTo("UP");
		assertThat(field(slug, "nextDueAt")).isEqualTo("2026-01-05T15:00:00Z");
		assertThat(this.events.forSlug(slug)).isEmpty();
	}

	private static void lockCheck(Connection connection, String key) throws SQLException {
		try (PreparedStatement lock = connection
			.prepareStatement("SELECT id FROM checks WHERE ping_key = ? FOR UPDATE")) {
			lock.setString(1, key);
			try (ResultSet rs = lock.executeQuery()) {
				assertThat(rs.next()).isTrue();
			}
		}
	}

	/** Waits until a statement waits for a row lock, or {@code task} is done. */
	private void awaitLockWait(Future<?> task) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement waiting = connection.prepareStatement(
						"SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()")) {
			while (!task.isDone() && System.nanoTime() < deadline) {
				try (ResultSet rs = waiting.executeQuery()) {
					rs.next();
					if (rs.getLong(1) > 0) {
						return;
					}
				}
				Thread.sleep(10);
			}
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	@Test
	void notifierSkipsDeliveriesLockedByAnotherInstance() throws Exception {
		Project project = newProject();
		String hook = "/hook/" + UUID.randomUUID();
		String channel = createChannel(project,
				"{\"type\":\"webhook\",\"config\":{\"url\":\"" + RECEIVER.url(hook) + "\"}}");
		goDown(project);
		// Another instance has claimed the delivery and is sending it.
		try (Connection other = this.dataSource.getConnection()) {
			other.setAutoCommit(false);
			try (PreparedStatement lock = other
				.prepareStatement("SELECT id FROM deliveries WHERE channel_id = ? FOR UPDATE")) {
				lock.setString(1, channel);
				try (ResultSet rs = lock.executeQuery()) {
					assertThat(rs.next()).isTrue();
				}
			}
			this.dispatcher.runOnce();
			assertThat(RECEIVER.requests(hook)).isEmpty();
			other.rollback();
		}
		this.dispatcher.runOnce();
		assertThat(RECEIVER.requests(hook)).hasSize(1);
	}

}
