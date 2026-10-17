package com.steelreed.cronbell.server.checks.internal;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ChecksPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void noLimitByDefaultOrWhenEmpty() {
		this.runner.run((context) -> assertThat(context.getBean(ChecksProperties.class).maxPerProject()).isNull());
		// What application.properties gives when CRONBELL_PROJECT_MAX_CHECKS is unset.
		this.runner.withPropertyValues("cronbell.checks.max-per-project=")
			.run((context) -> assertThat(context.getBean(ChecksProperties.class).maxPerProject()).isNull());
	}

	@Test
	void limitMustBePositive() {
		this.runner.withPropertyValues("cronbell.checks.max-per-project=20")
			.run((context) -> assertThat(context.getBean(ChecksProperties.class).maxPerProject()).isEqualTo(20));
		this.runner.withPropertyValues("cronbell.checks.max-per-project=0").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("CRONBELL_PROJECT_MAX_CHECKS) must be at least 1");
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ChecksProperties.class)
	static class Config {

	}

}
