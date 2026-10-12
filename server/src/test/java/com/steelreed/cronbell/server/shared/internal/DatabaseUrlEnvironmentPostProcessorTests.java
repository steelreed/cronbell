package com.steelreed.cronbell.server.shared.internal;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DatabaseUrlEnvironmentPostProcessorTests {

	@Test
	void convertsAPostgresUrl() {
		Map<String, Object> properties = DatabaseUrlEnvironmentPostProcessor
			.toDataSourceProperties("postgres://cron:p%40ss@db.example:6543/cron?sslmode=require");
		assertThat(properties)
			.containsEntry("spring.datasource.url", "jdbc:postgresql://db.example:6543/cron?sslmode=require")
			.containsEntry("spring.datasource.username", "cron")
			.containsEntry("spring.datasource.password", "p@ss");
	}

	@Test
	void portAndCredentialsAreOptional() {
		Map<String, Object> properties = DatabaseUrlEnvironmentPostProcessor
			.toDataSourceProperties("postgresql://localhost/cron");
		assertThat(properties).containsOnly(Map.entry("spring.datasource.url", "jdbc:postgresql://localhost/cron"));
	}

	@Test
	void passesJdbcUrlsThrough() {
		assertThat(DatabaseUrlEnvironmentPostProcessor.toDataSourceProperties("jdbc:postgresql://h/db"))
			.containsOnly(Map.entry("spring.datasource.url", "jdbc:postgresql://h/db"));
	}

	@Test
	void rejectsOtherSchemes() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> DatabaseUrlEnvironmentPostProcessor.toDataSourceProperties("mysql://h/db"));
	}

	@Test
	void overridesTheSqliteDefault() {
		MockEnvironment environment = new MockEnvironment()
			.withProperty("spring.datasource.url", "jdbc:sqlite:./data/cronbell.db")
			.withProperty("CRONBELL_DATABASE_URL", "postgres://u:p@h/db");
		// No system environment source here, so ours goes first.
		new DatabaseUrlEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
		assertThat(environment.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://h/db");
	}

	@Test
	void doesNothingWithoutTheVariable() {
		MockEnvironment environment = new MockEnvironment();
		new DatabaseUrlEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
		assertThat(environment.getProperty("spring.datasource.url")).isNull();
	}

}
