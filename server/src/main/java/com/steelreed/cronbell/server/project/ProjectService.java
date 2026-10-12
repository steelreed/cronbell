package com.steelreed.cronbell.server.project;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.steelreed.cronbell.server.shared.database.Ids;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Projects and their API keys. Only a SHA-256 hash of each key is stored. */
@Service
public class ProjectService {

	/** Prefix of every generated key, so leaked keys are easy to recognise. */
	public static final String KEY_PREFIX = "cb_";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;

	private final Clock clock;

	public ProjectService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** A stored API key. The key itself is never stored, only its first characters. */
	public record ApiKey(String id, String name, String prefix, Instant createdAt) {
	}

	/** A project as seen by one of its members. */
	public record Membership(String projectId, String projectName, String role) {
	}

	public boolean anyProjectExists() {
		return this.jdbc.sql("SELECT COUNT(*) FROM projects").query(Long.class).single() > 0;
	}

	@Transactional
	public String createProject(String name) {
		String id = Ids.newId();
		this.jdbc.sql("INSERT INTO projects (id, name, created_at) VALUES (?, ?, ?)")
			.params(id, name, this.clock.millis())
			.update();
		return id;
	}

	/** The oldest project, which the first account owns. */
	public Optional<String> findOldestProjectId() {
		return this.jdbc.sql("SELECT id FROM projects ORDER BY created_at, id LIMIT 1").query(String.class).optional();
	}

	@Transactional
	public void addMember(String projectId, String accountId, String role) {
		this.jdbc.sql("INSERT INTO project_members (project_id, account_id, role, created_at) VALUES (?, ?, ?, ?)")
			.params(projectId, accountId, role, this.clock.millis())
			.update();
	}

	/** Projects of an account, oldest membership first. */
	public List<Membership> findMemberships(String accountId) {
		return this.jdbc.sql("""
				SELECT p.id, p.name, m.role FROM project_members m JOIN projects p ON p.id = m.project_id
				 WHERE m.account_id = ? ORDER BY m.created_at, p.id""")
			.param(accountId)
			.query((rs, row) -> new Membership(rs.getString(1), rs.getString(2), rs.getString(3)))
			.list();
	}

	/** Generates a new key for the project and returns it. It cannot be shown again. */
	public String createApiKey(String projectId, String name) {
		return createApiKeyWithId(projectId, name).key();
	}

	/** A key just made: its stored description and the key, shown once. */
	public record NewApiKey(ApiKey apiKey, String key) {
	}

	/** Generates a new key for the project. The key cannot be shown again. */
	@Transactional
	public NewApiKey createApiKeyWithId(String projectId, String name) {
		byte[] random = new byte[32];
		RANDOM.nextBytes(random);
		String key = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
		return new NewApiKey(addApiKey(projectId, name, key), key);
	}

	/** Stores the hash of a key chosen by the operator. */
	@Transactional
	public ApiKey addApiKey(String projectId, String name, String key) {
		if (key.length() < 20) {
			throw new IllegalArgumentException("API key must be at least 20 characters");
		}
		String keyName = (name != null && !name.isBlank()) ? name.strip() : "API key";
		if (keyName.length() > 200) {
			throw new IllegalArgumentException("name must be at most 200 characters");
		}
		Instant now = Instant.ofEpochMilli(this.clock.millis());
		ApiKey apiKey = new ApiKey(Ids.newId(), keyName, key.substring(0, 8), now);
		this.jdbc.sql("""
				INSERT INTO api_keys (id, project_id, name, key_hash, key_prefix, created_at)
				VALUES (?, ?, ?, ?, ?, ?)""")
			.params(apiKey.id(), projectId, apiKey.name(), hash(key), apiKey.prefix(), now.toEpochMilli())
			.update();
		return apiKey;
	}

	/** Keys of a project, oldest first. */
	public List<ApiKey> listApiKeys(String projectId) {
		return this.jdbc
			.sql("SELECT id, name, key_prefix, created_at FROM api_keys WHERE project_id = ? ORDER BY created_at, id")
			.param(projectId)
			.query((rs, row) -> new ApiKey(rs.getString(1), rs.getString(2), rs.getString(3),
					Instant.ofEpochMilli(rs.getLong(4))))
			.list();
	}

	/**
	 * Deletes a key of the project. It stops working at once.
	 * @return whether the project had that key
	 */
	@Transactional
	public boolean revokeApiKey(String projectId, String id) {
		return this.jdbc.sql("DELETE FROM api_keys WHERE project_id = ? AND id = ?").params(projectId, id).update() > 0;
	}

	/** The project the key belongs to, if any. */
	public Optional<String> findProjectIdByApiKey(String key) {
		return this.jdbc.sql("SELECT project_id FROM api_keys WHERE key_hash = ?")
			.param(hash(key))
			.query(String.class)
			.optional();
	}

	static String hash(String key) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
