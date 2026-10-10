package com.steelreed.cron.server;

import org.springframework.boot.SpringApplication;

public class TestCronServerApplication {

	public static void main(String[] args) {
		SpringApplication.from(CronServerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
