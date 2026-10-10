package com.steelreed.cronbell.server;

import org.springframework.boot.SpringApplication;

public class TestCronbellApplication {

	public static void main(String[] args) {
		SpringApplication.from(CronbellApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
