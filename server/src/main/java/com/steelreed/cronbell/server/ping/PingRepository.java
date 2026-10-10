package com.steelreed.cronbell.server.ping;

import java.time.Instant;
import java.util.List;

import com.steelreed.cronbell.server.check.PingKind;

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
				int exitCode = rs.getInt("exit_code");
				return new Ping(rs.getString("id"), rs.getString("check_id"), PingKind.valueOf(rs.getString("kind")),
						rs.wasNull() ? null : exitCode, rs.getString("rid"), rs.getString("body"),
						Instant.ofEpochMilli(rs.getLong("received_at")), rs.getString("remote_addr"),
						rs.getString("user_agent"));
			})
			.list();
	}

}
