package com.steelreed.cronbell.server.history;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void retentionDefaultsToNinetyDays() {
		this.runner.run((context) -> {
			HistoryProperties properties = context.getBean(HistoryProperties.class);
			assertThat(properties.retention()).isEqualTo(Duration.ofDays(90));
			assertThat(properties.batchSize()).isEqualTo(5000);
		});
	}

	@Test
	void retentionAcceptsSimpleAndIsoDurations() {
		this.runner.withPropertyValues("cronbell.history.retention=30d")
			.run((context) -> assertThat(context.getBean(HistoryProperties.class).retention())
				.isEqualTo(Duration.ofDays(30)));
		this.runner.withPropertyValues("cronbell.history.retention=P365D")
			.run((context) -> assertThat(context.getBean(HistoryProperties.class).retention())
				.isEqualTo(Duration.ofDays(365)));
	}

	@Test
	void zeroOrNegativeRetentionIsRejectedAtStartup() {
		for (String value : new String[] { "0", "0d", "-1d" }) {
			this.runner.withPropertyValues("cronbell.history.retention=" + value).run((context) -> {
				assertThat(context).as(value).hasFailed();
				assertThat(context.getStartupFailure()).as(value)
					.rootCause()
					.hasMessageContaining("cronbell.history.retention (CRONBELL_HISTORY_RETENTION) must be a positive");
			});
		}
	}

	@Test
	void batchSizeMustBePositive() {
		this.runner.withPropertyValues("cronbell.history.batch-size=0")
			.run((context) -> assertThat(context).hasFailed());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(HistoryProperties.class)
	static class Config {

	}

}
