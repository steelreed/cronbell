package com.steelreed.cron.server;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
public class CronServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(CronServerApplication.class, args);
	}

	/** All time comes from here, so tests can move it. */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	/** The detector and housekeeping. Tests turn it off and drive the detector by hand. */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "cron.scheduling.enabled", havingValue = "true", matchIfMissing = true)
	@EnableScheduling
	static class SchedulingConfig {

	}

}
