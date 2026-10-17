package com.steelreed.cronbell.server.accounts.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.steelreed.cronbell.server.shared.database.Database;
import com.steelreed.cronbell.server.shared.database.Ids;
import com.steelreed.cronbell.server.shared.web.NotFoundException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects, their members and their API keys. Only a SHA-256 hash of each key is stored.
 */
@Service
public class ProjectService {

	/** Prefix of every generated key, so leaked keys are easy to recognise. */
	public static final String KEY_PREFIX = "cb_";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;

	private final Database database;

	private final Clock clock;

	public ProjectService(JdbcClient jdbc, Database database, Clock clock) {
		this.jdbc = jdbc;
		this.database = database;
		this.clock = clock;
	}

	/** What a member may do in a project (DESIGN.md §6, Roles). */
	public enum Role {

		OWNER, MEMBER;

		static Role ofNullable(String value) {
			return (value != null) ? valueOf(value) : null;
		}

	}

	/** A stored API key. The key itself is never stored, only its first characters. */
	public record ApiKey(String id, String name, String prefix, Instant createdAt) {
	}

	/** A project as seen by one of its members. */
	public record Membership(String projectId, String projectName, Role role) {
	}

	/** A member of a project. */
	public record Member(String accountId, String email, Role role, Instant joinedAt) {
	}

	/** No such member in the caller's project. */
	public static class MemberNotFoundException extends NotFoundException {

		public MemberNotFoundException(String accountId) {
			super("no member " + accountId);
		}

	}

	public boolean anyProjectExists() {
		return this.jdbc.sql("SELECT COUNT(*) FROM projects").query(Long.class).single() > 0;
	}

	@Transactional
	public String createProject(String name) {
		String id = Ids.newId();
		this.jdbc.sql("INSERT INTO projects (id, name, created_at) VALUES (?, ?, ?)")
			.params(id, validateName(name), this.clock.millis())
			.update();
		return id;
	}

	/** The oldest project, which the first account owns. */
	public Optional<String> findOldestProjectId() {
		return this.jdbc.sql("SELECT id FROM projects ORDER BY created_at, id LIMIT 1").query(String.class).optional();
	}

	public Optional<String> findProjectName(String projectId) {
		return this.jdbc.sql("SELECT name FROM projects WHERE id = ?").param(projectId).query(String.class).optional();
	}

	/**
	 * A project name, stripped.
	 * @throws IllegalArgumentException if it is blank or longer than 200 characters
	 */
	public static String validateName(String name) {
		String result = (name != null) ? name.strip() : "";
		if (result.isEmpty() || result.length() > 200) {
			throw new IllegalArgumentException("project name must be 1 to 200 characters");
		}
		return result;
	}

	@Transactional
	public void addMember(String projectId, String accountId, Role role) {
		this.jdbc.sql("INSERT INTO project_members (project_id, account_id, role, created_at) VALUES (?, ?, ?, ?)")
			.params(projectId, accountId, role.name(), this.clock.millis())
			.update();
	}

	/** Projects of an account, oldest membership first. */
	public List<Membership> findMemberships(String accountId) {
		return this.jdbc.sql("""
				SELECT p.id, p.name, m.role FROM project_members m JOIN projects p ON p.id = m.project_id
				 WHERE m.account_id = ? ORDER BY m.created_at, p.id""")
			.param(accountId)
			.query((rs, row) -> new Membership(rs.getString(1), rs.getString(2), Role.valueOf(rs.getString(3))))
			.list();
	}

	/** The account's membership of the project, if it is a member. */
	public Optional<Membership> findMembership(String accountId, String projectId) {
		return findMemberships(accountId).stream()
			.filter((membership) -> membership.projectId().equals(projectId))
			.findFirst();
	}

	/** Members of a project: owners first, then by when they joined. */
	public List<Member> listMembers(String projectId) {
		return this.jdbc.sql("""
				SELECT a.id, a.email, m.role, m.created_at
				  FROM project_members m JOIN accounts a ON a.id = m.account_id
				 WHERE m.project_id = ?
				 ORDER BY CASE m.role WHEN 'OWNER' THEN 0 ELSE 1 END, m.created_at, a.id""")
			.param(projectId)
			.query((rs, row) -> new Member(rs.getString(1), rs.getString(2), Role.valueOf(rs.getString(3)),
					Instant.ofEpochMilli(rs.getLong(4))))
			.list();
	}

	/**
	 * Removes an account from a project; the account stays. The project row is locked
	 * first (on PostgreSQL), so two owners removing each other at once cannot leave the
	 * project without an owner.
	 * @throws MemberNotFoundException if the account is not a member
	 * @throws AccountConflictException if it is the last owner
	 */
	@Transactional
	public void removeMember(String projectId, String accountId) {
		this.jdbc.sql("SELECT id FROM projects WHERE id = ?" + this.database.forUpdate())
			.param(projectId)
			.query(String.class)
			.optional();
		Role role = this.jdbc.sql("SELECT role FROM project_members WHERE project_id = ? AND account_id = ?")
			.params(projectId, accountId)
			.query((rs, row) -> Role.valueOf(rs.getString(1)))
			.optional()
			.orElseThrow(() -> new MemberNotFoundException(accountId));
		if (role == Role.OWNER) {
			long owners = this.jdbc.sql("SELECT COUNT(*) FROM project_members WHERE project_id = ? AND role = 'OWNER'")
				.param(projectId)
				.query(Long.class)
				.single();
			if (owners <= 1) {
				throw new AccountConflictException("the last owner of a project cannot be removed");
			}
		}
		this.jdbc.sql("DELETE FROM project_members WHERE project_id = ? AND account_id = ?")
			.params(projectId, accountId)
			.update();
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
		String key = KEY_PREFIX + randomToken();
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

	/** 32 random bytes in URL-safe Base64 without padding (43 characters). */
	static String randomToken() {
		byte[] random = new byte[32];
		RANDOM.nextBytes(random);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
	}

	/** SHA-256 of a key or token, hex. */
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
