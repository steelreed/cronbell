package com.steelreed.cronbell.server.checks.internal;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * When the detector last ran, on any instance: the one row of {@code detector_heartbeat}.
 * After a start, the time since tells how long the server was away (DESIGN.md §4,
 * Downtime).
 */
@Repository
class DetectorHeartbeat {

	private final JdbcClient jdbc;

	DetectorHeartbeat(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The latest beat; empty before the detector first ran. */
	Optional<Instant> last() {
		return this.jdbc.sql("SELECT beat_at FROM detector_heartbeat WHERE id = 1").query((rs, row) -> {
			long millis = rs.getLong("beat_at");
			return rs.wasNull() ? null : Instant.ofEpochMilli(millis);
		}).optional();
	}

	void beat(Instant now) {
		this.jdbc.sql("UPDATE detector_heartbeat SET beat_at = ? WHERE id = 1").param(now.toEpochMilli()).update();
	}

}
