package com.steelreed.cronbell.server.accounts.internal;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class InvitePropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void linksLastSevenDaysByDefault() {
		this.runner
			.run((context) -> assertThat(context.getBean(InviteProperties.class).ttl()).isEqualTo(Duration.ofDays(7)));
		this.runner.withPropertyValues("cronbell.invites.ttl=48h")
			.run((context) -> assertThat(context.getBean(InviteProperties.class).ttl())
				.isEqualTo(Duration.ofHours(48)));
	}

	@Test
	void zeroOrNegativeIsRejectedAtStartup() {
		for (String value : new String[] { "0", "-1d" }) {
			this.runner.withPropertyValues("cronbell.invites.ttl=" + value).run((context) -> {
				assertThat(context).as(value).hasFailed();
				assertThat(context.getStartupFailure()).as(value)
					.rootCause()
					.hasMessageContaining("CRONBELL_INVITE_TTL) must be a positive duration");
			});
		}
	}

	@Test
	void defaultProjectNameIsTheEmailsLocalPart() {
		assertThat(InviteService.projectName(null, "lan.nguyen@example.com")).isEqualTo("lan.nguyen's project");
		assertThat(InviteService.projectName("  ", "a@example.com")).isEqualTo("a's project");
		assertThat(InviteService.projectName(" Backups ", "a@example.com")).isEqualTo("Backups");
		assertThat(InviteService.projectName(null, "x".repeat(150) + "@example.com")).hasSize(110);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(InviteProperties.class)
	static class Config {

	}

}
