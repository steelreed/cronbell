package com.steelreed.cronbell.server.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

/** Which database we run on, and the SQL that differs between them. */
@Component
public class Database {

	public enum Kind {

		SQLITE, POSTGRESQL

	}

	private final Kind kind;

	public Database(DataSource dataSource) throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
			if (product.contains("sqlite")) {
				this.kind = Kind.SQLITE;
			}
			else if (product.contains("postgres")) {
				this.kind = Kind.POSTGRESQL;
			}
			else {
				throw new IllegalStateException("Unsupported database: " + product);
			}
		}
	}

	public Kind kind() {
		return this.kind;
	}

	/** Row lock for a read-modify-write. SQLite has one writer and needs none. */
	public String forUpdate() {
		return (this.kind == Kind.POSTGRESQL) ? " FOR UPDATE" : "";
	}

	/** Row claim for work shared between instances. SQLite runs a single instance. */
	public String forUpdateSkipLocked() {
		return (this.kind == Kind.POSTGRESQL) ? " FOR UPDATE SKIP LOCKED" : "";
	}

}
