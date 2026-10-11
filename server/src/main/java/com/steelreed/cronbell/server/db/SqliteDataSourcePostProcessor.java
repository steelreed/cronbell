package com.steelreed.cronbell.server.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Settings SQLite needs, whatever the configured URL: the data directory must exist,
 * there is exactly one connection (SQLite has one writer, so a pool only adds lock
 * contention), WAL journal, foreign keys on, and a busy timeout.
 */
@Component
class SqliteDataSourcePostProcessor implements BeanPostProcessor {

	private static final String PREFIX = "jdbc:sqlite:";

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (bean instanceof HikariDataSource dataSource && dataSource.getJdbcUrl() != null
				&& dataSource.getJdbcUrl().startsWith(PREFIX)) {
			createParentDirectory(dataSource.getJdbcUrl());
			dataSource.setMaximumPoolSize(1);
			dataSource.setMinimumIdle(1);
			dataSource.addDataSourceProperty("journal_mode", "WAL");
			dataSource.addDataSourceProperty("synchronous", "NORMAL");
			dataSource.addDataSourceProperty("foreign_keys", "true");
			dataSource.addDataSourceProperty("busy_timeout", "10000");
		}
		return bean;
	}

	private static void createParentDirectory(String url) {
		String file = url.substring(PREFIX.length());
		int query = file.indexOf('?');
		if (query >= 0) {
			file = file.substring(0, query);
		}
		if (file.isEmpty() || file.startsWith(":memory:") || file.startsWith("file:")) {
			return;
		}
		Path parent = Path.of(file).toAbsolutePath().getParent();
		try {
			Files.createDirectories(parent);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot create the data directory " + parent, ex);
		}
	}

}
