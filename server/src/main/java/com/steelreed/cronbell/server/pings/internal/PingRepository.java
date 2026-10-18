package com.steelreed.cronbell.server.pings.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.steelreed.cronbell.server.checks.PingKind;
import com.steelreed.cronbell.server.pings.Ping;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PingRepository {

	private final JdbcClient jdbc;

	public PingRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(Ping ping) {
		this.jdbc.sql("""
				INSERT INTO pings (id, check_id, kind, exit_code, rid, body, received_at, remote_addr, user_agent)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
			.params(ping.id(), ping.checkId(), ping.kind().name(), ping.exitCode(), ping.rid(), ping.body(),
					ping.receivedAt().toEpochMilli(), ping.remoteAddr(), ping.userAgent())
			.update();
	}

	/** Most recent first. */
	public List<Ping> findRecent(String checkId, int limit) {
		return this.jdbc.sql("""
				SELECT id, check_id, kind, exit_code, rid, body, received_at, remote_addr, user_agent
				  FROM pings WHERE check_id = ? ORDER BY received_at DESC, id DESC LIMIT ?""")
			.param(checkId)
			.param(limit)
			.query((rs, row) -> {
				int code = rs.getInt("exit_code");
				Integer exitCode = rs.wasNull() ? null : code;
				return new Ping(rs.getString("id"), rs.getString("check_id"), PingKind.valueOf(rs.getString("kind")),
						exitCode, rs.getString("rid"), rs.getString("body"),
						Instant.ofEpochMilli(rs.getLong("received_at")), rs.getString("remote_addr"),
						rs.getString("user_agent"));
			})
			.list();
	}

	/**
	 * The {@code start}, {@code success} and {@code fail} pings of a check received since
	 * {@code since}, newest first, at most {@code limit}, without their bodies.
	 */
	public List<Ping> findRunPingsSince(String checkId, Instant since, int limit) {
		return this.jdbc.sql("""
				SELECT id, check_id, kind, exit_code, rid, received_at FROM pings
				 WHERE check_id = ? AND kind <> 'LOG' AND received_at >= ?
				 ORDER BY received_at DESC, id DESC LIMIT ?""")
			.param(checkId)
			.param(since.toEpochMilli())
			.param(limit)
			.query((rs, row) -> {
				int code = rs.getInt("exit_code");
				Integer exitCode = rs.wasNull() ? null : code;
				return new Ping(rs.getString("id"), rs.getString("check_id"), PingKind.valueOf(rs.getString("kind")),
						exitCode, rs.getString("rid"), null, Instant.ofEpochMilli(rs.getLong("received_at")), null,
						null);
			})
			.list();
	}

	/** A finished run: a {@code success} or {@code fail} ping. */
	public record FinishedRun(Instant at, PingKind kind, Integer exitCode) {
	}

	/** How many {@code success} or {@code fail} pings of a check fell in one bucket. */
	public record BucketCount(String checkId, int bucket, PingKind kind, long count) {
	}

	/**
	 * The latest finished runs of a check, most recent first. Uses the
	 * {@code (check_id, received_at)} index backwards and stops after {@code limit} rows.
	 */
	public List<FinishedRun> findRecentFinished(String checkId, int limit) {
		return this.jdbc.sql("""
				SELECT kind, exit_code, received_at FROM pings
				 WHERE check_id = ? AND kind IN ('SUCCESS', 'FAIL')
				 ORDER BY received_at DESC, id DESC LIMIT ?""").param(checkId).param(limit).query((rs, row) -> {
			int code = rs.getInt("exit_code");
			Integer exitCode = rs.wasNull() ? null : code;
			return new FinishedRun(Instant.ofEpochMilli(rs.getLong("received_at")),
					PingKind.valueOf(rs.getString("kind")), exitCode);
		}).list();
	}

	/**
	 * Counts the {@code success} and {@code fail} pings per check and bucket, where
	 * bucket {@code i} is {@code boundaries[i] <= received_at < boundaries[i + 1]}. One
	 * statement: the range is read through the {@code (check_id, received_at)} index and
	 * the bucket is picked with a {@code CASE} over the boundaries.
	 * @param boundaries at least two instants, ascending
	 */
	public List<BucketCount> countFinishedByBucket(Collection<String> checkIds, List<Instant> boundaries) {
		if (checkIds.isEmpty() || boundaries.size() < 2) {
			return List.of();
		}
		int last = boundaries.size() - 2;
		StringBuilder bucket = new StringBuilder();
		if (last == 0) {
			bucket.append("0");
		}
		else {
			bucket.append("CASE");
			for (int i = 1; i <= last; i++) {
				bucket.append(" WHEN received_at < :b").append(i).append(" THEN ").append(i - 1);
			}
			bucket.append(" ELSE ").append(last).append(" END");
		}
		var spec = this.jdbc.sql("""
				SELECT check_id, bucket, kind, COUNT(*) AS n FROM (
				  SELECT check_id, %s AS bucket, kind FROM pings
				   WHERE check_id IN (:ids) AND kind IN ('SUCCESS', 'FAIL')
				     AND received_at >= :start AND received_at < :end
				) counted GROUP BY check_id, bucket, kind""".formatted(bucket))
			.param("ids", checkIds)
			.param("start", boundaries.getFirst().toEpochMilli())
			.param("end", boundaries.getLast().toEpochMilli());
		for (int i = 1; i <= last; i++) {
			spec = spec.param("b" + i, boundaries.get(i).toEpochMilli());
		}
		return spec
			.query((rs, row) -> new BucketCount(rs.getString("check_id"), rs.getInt("bucket"),
					PingKind.valueOf(rs.getString("kind")), rs.getLong("n")))
			.list();
	}

	/**
	 * Deletes up to {@code limit} pings received before {@code cutoff}, except what the
	 * state machine and alerts still read (DESIGN.md §9, History): the latest ping of
	 * each check, its latest ping with a body, and the pings of its open run. A ping is
	 * deletable only if a newer ping of the same check exists, and, when it has a body, a
	 * newer one with a body. Returns how many were deleted.
	 */
	public int deleteBefore(Instant cutoff, int limit) {
		return this.jdbc.sql("""
				DELETE FROM pings WHERE id IN (
				  SELECT p.id FROM pings p
				   WHERE p.received_at < :cutoff
				     AND EXISTS (SELECT 1 FROM pings n
				                  WHERE n.check_id = p.check_id
				                    AND n.received_at >= p.received_at
				                    AND (n.received_at > p.received_at OR n.id > p.id)
				                    AND (p.body IS NULL OR n.body IS NOT NULL))
				     AND NOT EXISTS (SELECT 1 FROM checks c
				                      WHERE c.id = p.check_id AND c.run_started_at IS NOT NULL
				                        AND p.received_at >= c.run_started_at)
				   LIMIT :limit)""").param("cutoff", cutoff.toEpochMilli()).param("limit", limit).update();
	}

}
