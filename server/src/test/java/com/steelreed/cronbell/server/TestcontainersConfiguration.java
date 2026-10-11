package com.steelreed.cronbell.server;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

/**
 * PostgreSQL for {@link TestCronbellApplication} (run the server locally against
 * Postgres).
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(POSTGRES_IMAGE);
	}

}
