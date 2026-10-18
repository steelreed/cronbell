package com.steelreed.cronbell.server.alerts.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ChannelRepository {

	private static final String COLUMNS = "id, project_id, type, name, config, created_at, updated_at";

	private static final TypeReference<LinkedHashMap<String, String>> CONFIG = new TypeReference<>() {
	};

	private final JdbcClient jdbc;

	private final JsonMapper json;

	public ChannelRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	public List<Channel> findAllByProject(String projectId) {
		return this.jdbc.sql("""
				SELECT %s
				  FROM channels
				 WHERE project_id = ?
				 ORDER BY created_at, id""".formatted(COLUMNS)).param(projectId).query(this::map).list();
	}

	public Set<String> findIdsByProject(String projectId) {
		return Set.copyOf(this.jdbc.sql("SELECT id FROM channels WHERE project_id = ?")
			.param(projectId)
			.query(String.class)
			.list());
	}

	public Optional<Channel> findById(String id) {
		return this.jdbc.sql("""
				SELECT %s
				  FROM channels
				 WHERE id = ?""".formatted(COLUMNS)).param(id).query(this::map).optional();
	}

	public void insert(Channel channel) {
		this.jdbc.sql("""
				INSERT INTO channels (%s)
				VALUES (?, ?, ?, ?, ?, ?, ?)""".formatted(COLUMNS))
			.params(channel.id(), channel.projectId(), channel.type().name(), channel.name(),
					this.json.writeValueAsString(channel.config()), channel.createdAt().toEpochMilli(),
					channel.updatedAt().toEpochMilli())
			.update();
	}

	public void update(Channel channel) {
		this.jdbc.sql("UPDATE channels SET name = ?, config = ?, updated_at = ? WHERE id = ?")
			.params(channel.name(), this.json.writeValueAsString(channel.config()), channel.updatedAt().toEpochMilli(),
					channel.id())
			.update();
	}

	public boolean delete(String projectId, String id) {
		return this.jdbc.sql("DELETE FROM channels WHERE project_id = ? AND id = ?").params(projectId, id).update() > 0;
	}

	private Channel map(ResultSet rs, int row) throws SQLException {
		Map<String, String> config = this.json.readValue(rs.getString("config"), CONFIG);
		return new Channel(rs.getString("id"), rs.getString("project_id"), ChannelType.valueOf(rs.getString("type")),
				rs.getString("name"), Map.copyOf(config), Instant.ofEpochMilli(rs.getLong("created_at")),
				Instant.ofEpochMilli(rs.getLong("updated_at")));
	}

}
