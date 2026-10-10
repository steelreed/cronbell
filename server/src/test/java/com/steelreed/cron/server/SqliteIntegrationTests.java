package com.steelreed.cron.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.steelreed.cron.server.db.Database;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** The default setup: SQLite in a fresh data directory. */
class SqliteIntegrationTests extends AbstractIntegrationTests {

	private static final Path DATA_DIR = createDataDir();

	@Autowired
	Database database;

	@DynamicPropertySource
	static void dataDir(DynamicPropertyRegistry registry) {
		registry.add("cron.data-dir", () -> DATA_DIR.resolve("nested").toString());
	}

	@Test
	void runsOnSqliteInTheDataDirectory() {
		assertThat(this.database.kind()).isEqualTo(Database.Kind.SQLITE);
		assertThat(DATA_DIR.resolve("nested/cron.db")).exists();
		assertThat(this.jdbc.sql("PRAGMA journal_mode").query(String.class).single()).isEqualToIgnoringCase("wal");
		assertThat(this.jdbc.sql("PRAGMA foreign_keys").query(Integer.class).single()).isEqualTo(1);
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
