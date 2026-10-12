package com.steelreed.cronbell.server.notify;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.steelreed.cronbell.server.db.Database;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The delivery outbox: one row per (alert, channel). */
@Repository
public class DeliveryRepository {

	private static final String COLUMNS = """
			id, channel_id, check_id, event, payload, status, attempts, next_attempt_at, last_attempt_at, last_error,
			created_at, delivered_at""";

	public enum Status {

		PENDING, DELIVERED, FAILED

	}

	/** A claimed delivery. {@code attempts} counts the attempt about to be made. */
	public record Delivery(String id, String channelId, String checkId, Alert alert, Status status, int attempts,
			Instant createdAt, String lastError) {
	}

	/** Recent delivery status of one channel. */
	public record ChannelStatus(Instant lastAttemptAt, Instant lastDeliveryAt, String lastError, int pending) {

		public static final ChannelStatus NONE = new ChannelStatus(null, null, null, 0);

	}

	private final JdbcClient jdbc;

	private final Database database;

	private final JsonMapper json;

	public DeliveryRepository(JdbcClient jdbc, Database database, JsonMapper json) {
		this.jdbc = jdbc;
		this.database = database;
		this.json = json;
	}

	public void insert(String id, String channelId, String checkId, Alert alert, Instant now) {
		this.jdbc.sql("""
				INSERT INTO deliveries (id, channel_id, check_id, event, payload, status, attempts, next_attempt_at,
				                        created_at)
				VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)""")
			.params(id, channelId, checkId, alert.kind().name(), this.json.writeValueAsString(alert),
					now.toEpochMilli(), now.toEpochMilli())
			.update();
	}

	/**
	 * Claims up to {@code limit} pending deliveries due at {@code now} and leases them
	 * until {@code leaseUntil}: other senders skip them until then. On PostgreSQL rows
	 * locked by another instance are skipped. Call in a transaction.
	 */
	public List<Delivery> claimDue(Instant now, Instant leaseUntil, int limit) {
		List<Delivery> due = this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM deliveries
				WHERE status = 'PENDING' AND next_attempt_at <= :now
				ORDER BY next_attempt_at
				LIMIT :limit""" + this.database.forUpdateSkipLocked())
			.param("now", now.toEpochMilli())
			.param("limit", limit)
			.query(this::map)
			.list();
		if (due.isEmpty()) {
			return due;
		}
		this.jdbc.sql("""
				UPDATE deliveries SET attempts = attempts + 1, next_attempt_at = :lease, last_attempt_at = :now
				 WHERE id IN (:ids)""")
			.param("lease", leaseUntil.toEpochMilli())
			.param("now", now.toEpochMilli())
			.param("ids", due.stream().map(Delivery::id).toList())
			.update();
		return due.stream()
			.map((d) -> new Delivery(d.id(), d.channelId(), d.checkId(), d.alert(), d.status(), d.attempts() + 1,
					d.createdAt(), d.lastError()))
			.toList();
	}

	public void markDelivered(String id, Instant now) {
		this.jdbc.sql("UPDATE deliveries SET status = 'DELIVERED', delivered_at = ?, last_error = NULL WHERE id = ?")
			.params(now.toEpochMilli(), id)
			.update();
	}

	public void markRetry(String id, Instant nextAttemptAt, String error) {
		this.jdbc.sql("UPDATE deliveries SET next_attempt_at = ?, last_error = ? WHERE id = ?")
			.params(nextAttemptAt.toEpochMilli(), truncate(error), id)
			.update();
	}

	public void markFailed(String id, String error) {
		this.jdbc.sql("UPDATE deliveries SET status = 'FAILED', last_error = ? WHERE id = ?")
			.params(truncate(error), id)
			.update();
	}

	/** Delivery status of every channel of the project that has deliveries. */
	public Map<String, ChannelStatus> statusByChannel(String projectId) {
		Map<String, Object[]> latest = new HashMap<>();
		this.jdbc.sql("""
				SELECT d.channel_id, d.status, d.last_error FROM deliveries d
				  JOIN channels c ON c.id = d.channel_id
				 WHERE c.project_id = ?
				   AND d.last_attempt_at = (SELECT MAX(x.last_attempt_at) FROM deliveries x
				                             WHERE x.channel_id = d.channel_id)""").param(projectId).query((rs) -> {
			latest.put(rs.getString("channel_id"), new Object[] { rs.getString("status"), rs.getString("last_error") });
		});
		Map<String, ChannelStatus> result = new HashMap<>();
		this.jdbc.sql("""
				SELECT d.channel_id, MAX(d.last_attempt_at) AS last_attempt_at, MAX(d.delivered_at) AS delivered_at,
				       SUM(CASE WHEN d.status = 'PENDING' THEN 1 ELSE 0 END) AS pending
				  FROM deliveries d JOIN channels c ON c.id = d.channel_id
				 WHERE c.project_id = ?
				 GROUP BY d.channel_id""").param(projectId).query((rs) -> {
			String channelId = rs.getString("channel_id");
			Object[] last = latest.get(channelId);
			String error = (last != null && !Status.DELIVERED.name().equals(last[0])) ? (String) last[1] : null;
			result.put(channelId, new ChannelStatus(instant(rs, "last_attempt_at"), instant(rs, "delivered_at"), error,
					rs.getInt("pending")));
		});
		return result;
	}

	/**
	 * Deletes up to {@code limit} delivered or failed deliveries created before
	 * {@code cutoff}. Pending ones are kept, whatever their age. Returns how many were
	 * deleted.
	 */
	public int deleteFinishedBefore(Instant cutoff, int limit) {
		return this.jdbc.sql("""
				DELETE FROM deliveries WHERE id IN (
				  SELECT id FROM deliveries
				   WHERE created_at < :cutoff AND status IN ('DELIVERED', 'FAILED')
				   LIMIT :limit)""").param("cutoff", cutoff.toEpochMilli()).param("limit", limit).update();
	}

	private Delivery map(ResultSet rs, int row) throws SQLException {
		return new Delivery(rs.getString("id"), rs.getString("channel_id"), rs.getString("check_id"),
				this.json.readValue(rs.getString("payload"), Alert.class), Status.valueOf(rs.getString("status")),
				rs.getInt("attempts"), Instant.ofEpochMilli(rs.getLong("created_at")), rs.getString("last_error"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : Instant.ofEpochMilli(value);
	}

	private static String truncate(String error) {
		return (error != null && error.length() > 500) ? error.substring(0, 499) + "…" : error;
	}

}
