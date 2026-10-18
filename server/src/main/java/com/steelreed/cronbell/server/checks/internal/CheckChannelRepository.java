package com.steelreed.cronbell.server.checks.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The channels each check picked (DESIGN.md §8, Routing). No rows: every channel. */
@Repository
public class CheckChannelRepository {

	private final JdbcClient jdbc;

	public CheckChannelRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<String> findByCheck(String checkId) {
		return this.jdbc.sql("SELECT channel_id FROM check_channels WHERE check_id = ? ORDER BY channel_id")
			.param(checkId)
			.query(String.class)
			.list();
	}

	/**
	 * Check id to its channel ids, for the checks of the project that picked channels.
	 */
	public Map<String, List<String>> findByProject(String projectId) {
		Map<String, List<String>> result = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT cc.check_id, cc.channel_id
				  FROM check_channels cc JOIN checks c ON c.id = cc.check_id
				 WHERE c.project_id = ?
				 ORDER BY cc.check_id, cc.channel_id""").param(projectId).query((rs) -> {
			result.computeIfAbsent(rs.getString("check_id"), (key) -> new ArrayList<>())
				.add(rs.getString("channel_id"));
		});
		return result;
	}

	public void replace(String checkId, Collection<String> channelIds) {
		this.jdbc.sql("DELETE FROM check_channels WHERE check_id = ?").param(checkId).update();
		for (String channelId : channelIds) {
			this.jdbc.sql("INSERT INTO check_channels (check_id, channel_id) VALUES (?, ?)")
				.params(checkId, channelId)
				.update();
		}
	}

}
