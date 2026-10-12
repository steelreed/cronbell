package com.steelreed.cronbell.server.ping;

import java.time.Instant;
import java.util.List;

import com.steelreed.cronbell.server.checks.PingKind;

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
