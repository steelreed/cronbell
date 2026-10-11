package com.steelreed.cronbell.server;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
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
