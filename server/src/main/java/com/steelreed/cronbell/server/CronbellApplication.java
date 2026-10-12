package com.steelreed.cronbell.server;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Cronbell server, a modular monolith: each package below this one is an application
 * module (checks, pings, alerts, accounts, history, shared), verified by
 * {@code ModularityTests}. See ARCHITECTURE.md.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Modulithic(systemName = "Cronbell", sharedModules = "shared")
public class CronbellApplication {

	public static void main(String[] args) {
		SpringApplication.run(CronbellApplication.class, args);
	}

	/** All time comes from here, so tests can move it. */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	/**
	 * The detector and housekeeping. Tests turn it off and drive the detector by hand.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "cronbell.scheduling.enabled", havingValue = "true", matchIfMissing = true)
	@EnableScheduling
	static class SchedulingConfig {

	}

}
