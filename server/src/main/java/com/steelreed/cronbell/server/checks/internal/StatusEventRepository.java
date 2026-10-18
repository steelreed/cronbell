package com.steelreed.cronbell.server.checks.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.steelreed.cronbell.server.checks.Check;
import com.steelreed.cronbell.server.checks.CheckState;
import com.steelreed.cronbell.server.checks.DownReason;
import com.steelreed.cronbell.server.checks.StatusEvent;
import com.steelreed.cronbell.server.shared.database.Ids;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The status history of checks: one row per status change (DESIGN.md §4).
 *
 * <p>
 * The events of a check are ordered by {@code (at, seq)}: {@code seq} counts them up per
 * check as they are written, under the check's row lock, so changes in the same
 * millisecond keep their order wherever the history is read or pruned.
 */
@Repository
public class StatusEventRepository {

	private static final String COLUMNS = "id, check_id, project_id, kind, reason, run_at, at";

	private final JdbcClient jdbc;

	public StatusEventRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Records that {@code check} entered {@code state} at {@code at}. The caller holds
	 * the check's row lock (on PostgreSQL), which keeps the check's events numbered in
	 * order.
	 */
	public StatusEvent record(Check check, StatusEvent.Kind kind, CheckState state, Instant at) {
		StatusEvent event = StatusEvent.of(Ids.newId(), check, kind, state, at);
		long seq = this.jdbc.sql("SELECT COALESCE(MAX(seq), 0) + 1 FROM status_events WHERE check_id = ?")
			.param(check.id())
			.query(Long.class)
			.single();
		this.jdbc.sql("INSERT INTO status_events (seq, %s) VALUES (?, ?, ?, ?, ?, ?, ?, ?)".formatted(COLUMNS))
			.params(seq, event.id(), event.checkId(), event.projectId(), event.kind().name(),
					(event.reason() != null) ? event.reason().name() : null,
					(event.runAt() != null) ? event.runAt().toEpochMilli() : null, at.toEpochMilli())
			.update();
		return event;
	}

	/** Records the change to {@code next} if its status differs from the check's. */
	public Optional<StatusEvent> recordChange(Check check, CheckState next, Instant at) {
		if (next.status() == check.state().status()) {
			return Optional.empty();
		}
		return Optional.of(record(check, StatusEvent.Kind.entering(next.status()), next, at));
	}

	/** The latest events of a check, newest first. */
	public List<StatusEvent> findRecentByCheck(String checkId, int limit) {
		return this.jdbc.sql("""
				SELECT %s FROM status_events
				 WHERE check_id = ? ORDER BY at DESC, seq DESC LIMIT ?""".formatted(COLUMNS))
			.param(checkId)
			.param(limit)
			.query(StatusEventRepository::map)
			.list();
	}

	/** The latest events of a project, newest first. */
	public List<StatusEvent> findRecentByProject(String projectId, int limit) {
		return this.jdbc.sql("""
				SELECT %s FROM status_events
				 WHERE project_id = ? ORDER BY at DESC, seq DESC, id DESC LIMIT ?""".formatted(COLUMNS))
			.param(projectId)
			.param(limit)
			.query(StatusEventRepository::map)
			.list();
	}

	/**
	 * The events of a check after {@code from}, oldest first, led by the latest one at or
	 * before it: everything that tells its status from {@code from} on.
	 */
	public List<StatusEvent> findTimeline(String checkId, Instant from) {
		return timeline("check_id", checkId, from);
	}

	/** {@link #findTimeline} for every check of a project at once. */
	public List<StatusEvent> findTimelines(String projectId, Instant from) {
		return timeline("project_id", projectId, from);
	}

	private List<StatusEvent> timeline(String column, String value, Instant from) {
		return this.jdbc.sql("""
				SELECT %s FROM status_events e
				 WHERE e.%s = :value
				   AND (e.at > :from
				        OR NOT EXISTS (SELECT 1 FROM status_events n
				                        WHERE n.check_id = e.check_id AND n.at <= :from
				                          AND (n.at > e.at OR (n.at = e.at AND n.seq > e.seq))))
				 ORDER BY e.at, e.seq""".formatted(COLUMNS, column))
			.param("value", value)
			.param("from", from.toEpochMilli())
			.query(StatusEventRepository::map)
			.list();
	}

	/**
	 * Deletes up to {@code limit} events older than {@code cutoff}, keeping the latest
	 * one of each check before it, so its status at the cutoff stays known. Returns how
	 * many were deleted.
	 */
	public int deleteBefore(Instant cutoff, int limit) {
		return this.jdbc.sql("""
				DELETE FROM status_events WHERE id IN (
				  SELECT e.id FROM status_events e
				   WHERE e.at < :cutoff
				     AND EXISTS (SELECT 1 FROM status_events n
				                  WHERE n.check_id = e.check_id AND n.at < :cutoff
				                    AND (n.at > e.at OR (n.at = e.at AND n.seq > e.seq)))
				   LIMIT :limit)""").param("cutoff", cutoff.toEpochMilli()).param("limit", limit).update();
	}

	private static StatusEvent map(ResultSet rs, int row) throws SQLException {
		String reason = rs.getString("reason");
		long runAt = rs.getLong("run_at");
		Instant run = rs.wasNull() ? null : Instant.ofEpochMilli(runAt);
		return new StatusEvent(rs.getString("id"), rs.getString("check_id"), rs.getString("project_id"),
				StatusEvent.Kind.valueOf(rs.getString("kind")), (reason != null) ? DownReason.valueOf(reason) : null,
				run, Instant.ofEpochMilli(rs.getLong("at")));
	}

}
