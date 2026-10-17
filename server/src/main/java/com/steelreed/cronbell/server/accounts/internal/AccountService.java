package com.steelreed.cronbell.server.accounts.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import com.steelreed.cronbell.server.shared.database.Ids;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounts and their passwords (DESIGN.md §6). The first account is made by the setup: it
 * is the instance admin and owns the oldest project. Every other account comes from an
 * invite.
 */
@Service
public class AccountService {

	public static final int MIN_PASSWORD_LENGTH = 8;

	public static final int MAX_PASSWORD_LENGTH = 128;

	private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

	private static final String COLUMNS = "id, email, instance_admin, session_version, created_at";

	private final JdbcClient jdbc;

	private final ProjectService projects;

	private final Clock clock;

	private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

	/** Compared against when the email is unknown, so both cases take as long. */
	private final String dummyHash = this.passwordEncoder.encode("cronbell-no-such-account");

	public AccountService(JdbcClient jdbc, ProjectService projects, Clock clock) {
		this.jdbc = jdbc;
		this.projects = projects;
		this.clock = clock;
	}

	public boolean anyAccountExists() {
		return this.jdbc.sql("SELECT COUNT(*) FROM accounts").query(Long.class).single() > 0;
	}

	/**
	 * Creates the first account, the instance admin, and makes it the owner of the oldest
	 * project (created if there is none). Only one call can ever succeed while the
	 * account exists.
	 * @throws SetupAlreadyDoneException if an account exists, or another setup won a race
	 * @throws IllegalArgumentException if the email or password is invalid
	 */
	@Transactional
	public Account createFirstAccount(String email, String password) {
		String normalized = validateEmail(email);
		validatePassword(password);
		if (anyAccountExists()) {
			throw new SetupAlreadyDoneException();
		}
		Instant now = now();
		Account account = new Account(Ids.newId(), normalized, true, 0, now);
		try {
			this.jdbc.sql("""
					INSERT INTO accounts (id, email, password_hash, setup_slot, instance_admin, created_at, updated_at)
					VALUES (?, ?, ?, 1, 1, ?, ?)""")
				.params(account.id(), account.email(), this.passwordEncoder.encode(password), now.toEpochMilli(),
						now.toEpochMilli())
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			// A concurrent setup took the slot first.
			throw new SetupAlreadyDoneException();
		}
		String projectId = this.projects.findOldestProjectId().orElseGet(() -> this.projects.createProject("Default"));
		this.projects.addMember(projectId, account.id(), ProjectService.Role.OWNER);
		return account;
	}

	/**
	 * Creates an account that is not the instance admin, without any project. Used when
	 * an invite is accepted, in the caller's transaction.
	 * @throws AccountConflictException if the email already has an account
	 * @throws IllegalArgumentException if the email or password is invalid
	 */
	@Transactional
	public Account createAccount(String email, String password) {
		String normalized = validateEmail(email);
		validatePassword(password);
		String taken = "an account with this email exists: sign in first, then open the link again";
		if (findByEmail(normalized).isPresent()) {
			throw new AccountConflictException(taken);
		}
		Instant now = now();
		Account account = new Account(Ids.newId(), normalized, false, 0, now);
		try {
			this.jdbc.sql("""
					INSERT INTO accounts (id, email, password_hash, created_at, updated_at)
					VALUES (?, ?, ?, ?, ?)""")
				.params(account.id(), account.email(), this.passwordEncoder.encode(password), now.toEpochMilli(),
						now.toEpochMilli())
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			throw new AccountConflictException(taken);
		}
		return account;
	}

	/**
	 * The account with this email and password. Takes about as long whether or not the
	 * email exists.
	 */
	public Optional<Account> authenticate(String email, String password) {
		if (email == null) {
			this.passwordEncoder.matches("", this.dummyHash);
			return Optional.empty();
		}
		return checkPassword(findStored("email = ?", email.strip().toLowerCase(Locale.ROOT)), password);
	}

	/** The account if {@code password} is its password. */
	public Optional<Account> authenticateById(String accountId, String password) {
		return checkPassword(findStored("id = ?", accountId), password);
	}

	private Optional<Account> checkPassword(Optional<Stored> stored, String password) {
		if (password == null || password.length() > MAX_PASSWORD_LENGTH) {
			this.passwordEncoder.matches("", this.dummyHash);
			return Optional.empty();
		}
		if (stored.isEmpty()) {
			this.passwordEncoder.matches(password, this.dummyHash);
			return Optional.empty();
		}
		return this.passwordEncoder.matches(password, stored.get().passwordHash()) ? Optional.of(stored.get().account())
				: Optional.empty();
	}

	private Optional<Stored> findStored(String condition, String value) {
		return this.jdbc.sql("SELECT %s, password_hash FROM accounts WHERE %s".formatted(COLUMNS, condition))
			.param(value)
			.query((rs, n) -> new Stored(map(rs, n), rs.getString("password_hash")))
			.optional();
	}

	private record Stored(Account account, String passwordHash) {
	}

	/**
	 * Replaces the password and raises the session version, so other sessions end.
	 * @return the account with its new session version
	 * @throws IllegalArgumentException if the new password is invalid
	 */
	@Transactional
	public Account changePassword(String accountId, String newPassword) {
		validatePassword(newPassword);
		this.jdbc.sql("""
				UPDATE accounts SET password_hash = ?, session_version = session_version + 1, updated_at = ?
				 WHERE id = ?""")
			.params(this.passwordEncoder.encode(newPassword), now().toEpochMilli(), accountId)
			.update();
		return findById(accountId).orElseThrow(() -> new IllegalStateException("no account " + accountId));
	}

	public Optional<Account> findById(String id) {
		return this.jdbc.sql("SELECT %s FROM accounts WHERE id = ?".formatted(COLUMNS))
			.param(id)
			.query(AccountService::map)
			.optional();
	}

	public Optional<Account> findByEmail(String email) {
		return this.jdbc.sql("SELECT %s FROM accounts WHERE email = ?".formatted(COLUMNS))
			.param(email.strip().toLowerCase(Locale.ROOT))
			.query(AccountService::map)
			.optional();
	}

	/** What a session needs on every request: the account and its role in a project. */
	public record SessionState(Account account, ProjectService.Role role) {
	}

	/**
	 * The account and its role in {@code projectId} (null role when it is not a member or
	 * the project id is null), in one query.
	 */
	public Optional<SessionState> findSessionState(String accountId, String projectId) {
		return this.jdbc.sql("""
				SELECT a.id, a.email, a.instance_admin, a.session_version, a.created_at, m.role
				  FROM accounts a
				  LEFT JOIN project_members m ON m.account_id = a.id AND m.project_id = ?
				 WHERE a.id = ?""")
			.params(projectId, accountId)
			.query((rs, n) -> new SessionState(map(rs, n), ProjectService.Role.ofNullable(rs.getString("role"))))
			.optional();
	}

	private static Account map(ResultSet rs, int row) throws SQLException {
		return new Account(rs.getString("id"), rs.getString("email"), rs.getInt("instance_admin") == 1,
				rs.getInt("session_version"), Instant.ofEpochMilli(rs.getLong("created_at")));
	}

	private Instant now() {
		return Instant.ofEpochMilli(this.clock.millis());
	}

	public static String validateEmail(String email) {
		String normalized = (email != null) ? email.strip().toLowerCase(Locale.ROOT) : "";
		if (normalized.length() > 254 || !EMAIL.matcher(normalized).matches()) {
			throw new IllegalArgumentException("email must be a valid address");
		}
		return normalized;
	}

	public static void validatePassword(String password) {
		if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
			throw new IllegalArgumentException(
					"password must be " + MIN_PASSWORD_LENGTH + " to " + MAX_PASSWORD_LENGTH + " characters");
		}
	}

}
