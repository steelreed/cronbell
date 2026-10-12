package com.steelreed.cronbell.server.db;

import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Leases on rows of {@code job_locks}, so that one instance at a time runs a housekeeping
 * job. Each call is a single conditional update, atomic on SQLite and PostgreSQL alike.
 * The job's row must exist (see the migrations).
 */
@Repository
public class JobLocks {

	private final JdbcClient jdbc;

	public JobLocks(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Takes the lease if nobody holds it at {@code now}. */
	public boolean tryAcquire(String name, String owner, Instant now, Instant until) {
		return this.jdbc.sql("""
				UPDATE job_locks SET locked_by = ?, locked_until = ?
				 WHERE name = ? AND locked_until <= ?""")
			.params(owner, until.toEpochMilli(), name, now.toEpochMilli())
			.update() == 1;
	}

	/** Extends a lease the owner still holds. False if it was lost. */
	public boolean renew(String name, String owner, Instant until) {
		return this.jdbc.sql("UPDATE job_locks SET locked_until = ? WHERE name = ? AND locked_by = ?")
			.params(until.toEpochMilli(), name, owner)
			.update() == 1;
	}

	public void release(String name, String owner) {
		this.jdbc.sql("UPDATE job_locks SET locked_by = NULL, locked_until = 0 WHERE name = ? AND locked_by = ?")
			.params(name, owner)
			.update();
	}

}
