package com.steelreed.cron.server.project;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import com.steelreed.cron.server.db.Ids;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Projects and their API keys. Only a SHA-256 hash of each key is stored. */
@Service
public class ProjectService {

	/** Prefix of every generated key, so leaked keys are easy to recognise. */
	public static final String KEY_PREFIX = "crn_";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;

	private final Clock clock;

	public ProjectService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
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

	/** Generates a new key for the project and returns it. It cannot be shown again. */
	public String createApiKey(String projectId, String name) {
		byte[] random = new byte[32];
		RANDOM.nextBytes(random);
		String key = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
		addApiKey(projectId, name, key);
		return key;
	}

	/** Stores the hash of a key chosen by the operator. */
	@Transactional
	public void addApiKey(String projectId, String name, String key) {
		if (key.length() < 20) {
			throw new IllegalArgumentException("API key must be at least 20 characters");
		}
		this.jdbc.sql("""
				INSERT INTO api_keys (id, project_id, name, key_hash, key_prefix, created_at)
				VALUES (?, ?, ?, ?, ?, ?)""")
			.params(Ids.newId(), projectId, name, hash(key), key.substring(0, 8), this.clock.millis())
			.update();
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
