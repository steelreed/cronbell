package com.steelreed.cronbell.server.check;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.steelreed.cronbell.server.db.Database;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CheckRepository {

	private static final String COLUMNS = """
			id, ping_key, project_id, slug, name, schedule_kind, cron_expression, time_zone, period_seconds,
			grace_seconds, max_runtime_seconds, status, down_reason, last_ping_at, last_start_at, last_success_at,
			next_due_at, run_started_at, run_rid, created_at""";

	private static final RowMapper<Check> MAPPER = CheckRepository::map;

	private final JdbcClient jdbc;

	private final Database database;

	public CheckRepository(JdbcClient jdbc, Database database) {
		this.jdbc = jdbc;
		this.database = database;
	}

	/**
	 * Finds a check by ping key and, on PostgreSQL, locks its row until the transaction
	 * ends.
	 */
	public Optional<Check> findByPingKeyForUpdate(UUID pingKey) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM checks WHERE ping_key = ?" + this.database.forUpdate())
			.param(pingKey.toString())
			.query(MAPPER)
			.optional();
	}

	public Optional<Check> findById(String id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM checks WHERE id = ?").param(id).query(MAPPER).optional();
	}

	public Optional<Check> findBySlug(String projectId, String slug) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM checks WHERE project_id = ? AND slug = ?")
			.param(projectId)
			.param(slug)
			.query(MAPPER)
			.optional();
	}

	public List<Check> findAllByProject(String projectId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM checks WHERE project_id = ? ORDER BY slug")
			.param(projectId)
			.query(MAPPER)
			.list();
	}

	/**
	 * Claims UP checks that may have gone DOWN at {@code now}: past due plus grace
	 * without a ping since, or running longer than allowed. Mirrors
	 * {@link CheckStateMachine#detect}. On PostgreSQL rows are locked and rows locked by
	 * other instances are skipped.
	 */
	public List<Check> claimOverdue(Instant now, int limit) {
		String sql = "SELECT " + COLUMNS + """
				 FROM checks
				WHERE status = 'UP'
				  AND ((run_started_at IS NOT NULL
				        AND run_started_at + COALESCE(max_runtime_seconds, grace_seconds) * 1000 < :now)
				    OR (next_due_at IS NOT NULL AND next_due_at + grace_seconds * 1000 < :now
				        AND (last_ping_at IS NULL OR last_ping_at < next_due_at)))
				ORDER BY next_due_at
				LIMIT :limit""" + this.database.forUpdateSkipLocked();
		return this.jdbc.sql(sql).param("now", now.toEpochMilli()).param("limit", limit).query(MAPPER).list();
	}

	public void insert(Check check, Instant now) {
		CheckState s = check.state();
		this.jdbc
			.sql("INSERT INTO checks (" + COLUMNS + ", updated_at) VALUES "
					+ "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
			.params(check.id(), check.pingKey().toString(), check.projectId(), check.slug(), check.name(),
					check.scheduleKind().name(), check.cronExpression(), check.timeZone().getId(),
					check.periodSeconds(), check.graceSeconds(), check.maxRuntimeSeconds(), s.status().name(),
					name(s.downReason()), millis(s.lastPingAt()), millis(s.lastStartAt()), millis(s.lastSuccessAt()),
					millis(s.nextDueAt()), millis(s.runStartedAt()), s.runRid(), millis(check.createdAt()),
					now.toEpochMilli())
			.update();
	}

	/** Updates the definition and the state of an existing check. */
	public void update(Check check, Instant now) {
		CheckState s = check.state();
		this.jdbc.sql("""
				UPDATE checks SET name = ?, schedule_kind = ?, cron_expression = ?, time_zone = ?, period_seconds = ?,
				       grace_seconds = ?, max_runtime_seconds = ?, status = ?, down_reason = ?, last_ping_at = ?,
				       last_start_at = ?, last_success_at = ?, next_due_at = ?, run_started_at = ?, run_rid = ?,
				       updated_at = ?
				 WHERE id = ?""")
			.params(check.name(), check.scheduleKind().name(), check.cronExpression(), check.timeZone().getId(),
					check.periodSeconds(), check.graceSeconds(), check.maxRuntimeSeconds(), s.status().name(),
					name(s.downReason()), millis(s.lastPingAt()), millis(s.lastStartAt()), millis(s.lastSuccessAt()),
					millis(s.nextDueAt()), millis(s.runStartedAt()), s.runRid(), now.toEpochMilli(), check.id())
			.update();
	}

	/** Updates only the state of a check. */
	public void updateState(String checkId, CheckState s, Instant now) {
		this.jdbc.sql("""
				UPDATE checks SET status = ?, down_reason = ?, last_ping_at = ?, last_start_at = ?,
				       last_success_at = ?, next_due_at = ?, run_started_at = ?, run_rid = ?, updated_at = ?
				 WHERE id = ?""")
			.params(s.status().name(), name(s.downReason()), millis(s.lastPingAt()), millis(s.lastStartAt()),
					millis(s.lastSuccessAt()), millis(s.nextDueAt()), millis(s.runStartedAt()), s.runRid(),
					now.toEpochMilli(), checkId)
			.update();
	}

	private static Check map(ResultSet rs, int row) throws SQLException {
		CheckState state = new CheckState(CheckStatus.valueOf(rs.getString("status")),
				enumOrNull(rs.getString("down_reason")), instant(rs, "last_ping_at"), instant(rs, "last_start_at"),
				instant(rs, "last_success_at"), instant(rs, "next_due_at"), instant(rs, "run_started_at"),
				rs.getString("run_rid"));
		return new Check(rs.getString("id"), UUID.fromString(rs.getString("ping_key")), rs.getString("project_id"),
				rs.getString("slug"), rs.getString("name"), ScheduleKind.valueOf(rs.getString("schedule_kind")),
				rs.getString("cron_expression"), ZoneId.of(rs.getString("time_zone")), longOrNull(rs, "period_seconds"),
				rs.getLong("grace_seconds"), longOrNull(rs, "max_runtime_seconds"), state,
				Instant.ofEpochMilli(rs.getLong("created_at")));
	}

	private static DownReason enumOrNull(String value) {
		return (value != null) ? DownReason.valueOf(value) : null;
	}

	private static Long longOrNull(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Long millis = longOrNull(rs, column);
		return (millis != null) ? Instant.ofEpochMilli(millis) : null;
	}

	private static Long millis(Instant instant) {
		return (instant != null) ? instant.toEpochMilli() : null;
	}

	private static String name(Enum<?> value) {
		return (value != null) ? value.name() : null;
	}

}
