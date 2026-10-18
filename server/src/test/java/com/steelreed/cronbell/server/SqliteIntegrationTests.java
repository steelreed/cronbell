package com.steelreed.cronbell.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.steelreed.cronbell.server.shared.database.Database;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** The default setup: SQLite in a fresh data directory. */
class SqliteIntegrationTests extends AbstractProjectsIntegrationTests {

	private static final Path DATA_DIR = createDataDir();

	@Autowired
	Database database;

	@DynamicPropertySource
	static void dataDir(DynamicPropertyRegistry registry) {
		registry.add("cronbell.data-dir", () -> DATA_DIR.resolve("nested").toString());
	}

	@Test
	void runsOnSqliteInTheDataDirectory() {
		assertThat(this.database.kind()).isEqualTo(Database.Kind.SQLITE);
		assertThat(DATA_DIR.resolve("nested/cronbell.db")).exists();
		assertThat(this.jdbc.sql("PRAGMA journal_mode").query(String.class).single()).isEqualToIgnoringCase("wal");
		assertThat(this.jdbc.sql("PRAGMA foreign_keys").query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void openApiDescriptionIsPublic() {
		ResponseEntity<String> response = this.http.get().uri("/api/v1/openapi.json").retrieve().toEntity(String.class);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody()).contains("\"openapi\"").contains("/api/v1/checks/{slug}");
	}

	@Test
	void dashboardRoutesLoadTheWebApp() {
		for (String path : new String[] { "/", "/checks", "/checks/nightly-backup", "/members",
				"/invite/Ab-_0123456789abcdefghijklmnopqrstuvwxyzABCD" }) {
			ResponseEntity<String> response = this.http.get().uri(path).retrieve().toEntity(String.class);
			assertThat(response.getStatusCode().value()).as(path).isEqualTo(200);
			assertThat(response.getBody()).as(path).contains("Cronbell test dashboard");
		}
	}

	@Test
	void bundledAssetsAreServedAsFiles() {
		ResponseEntity<String> response = this.http.get().uri("/assets/app-test.js").retrieve().toEntity(String.class);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getHeaders().getContentType().toString()).contains("javascript");
		assertThat(response.getBody()).doesNotContain("Cronbell test dashboard");
		assertThat(this.http.get().uri("/assets/missing.js").retrieve().toEntity(String.class).getStatusCode().value())
			.isEqualTo(404);
	}

	@Test
	void apiAndPingPathsAreNotTheWebApp() {
		assertThat(this.http.get().uri("/api/v1/checks").retrieve().toEntity(String.class).getStatusCode().value())
			.isEqualTo(401);
		assertThat(this.http.get().uri("/ping").retrieve().toEntity(String.class).getBody())
			.doesNotContain("Cronbell test dashboard");
	}

	@Test
	void upgradesABeta1Database() throws IOException {
		Path file = Files.createTempFile("cronbell-upgrade", ".db");
		// Foreign keys on, as the server runs: V7 must not cascade-delete deliveries.
		String url = "jdbc:sqlite:" + file + "?foreign_keys=true";
		upgradeFromBeta1MakesTheSetupAccountInstanceAdmin(
				Flyway.configure().dataSource(url, null, null).target("4").load(),
				Flyway.configure().dataSource(url, null, null).load(),
				JdbcClient.create(new DriverManagerDataSource(url)));
	}

	private static Path createDataDir() {
		try {
			return Files.createTempDirectory("cron-sqlite-test");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
