package com.steelreed.cronbell.server.account;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import com.steelreed.cronbell.server.db.Ids;
import com.steelreed.cronbell.server.project.ProjectService;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounts and their passwords (DESIGN.md §6). v0.1 has one admin account, created by the
 * first-run setup, which owns the oldest project.
 */
@Service
public class AccountService {

	public static final int MIN_PASSWORD_LENGTH = 8;

	public static final int MAX_PASSWORD_LENGTH = 128;

	private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

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
	 * Creates the first account and makes it the owner of the oldest project (created if
	 * there is none). Only one call can ever succeed while the account exists.
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
		Instant now = Instant.ofEpochMilli(this.clock.millis());
		Account account = new Account(Ids.newId(), normalized, now);
		try {
			this.jdbc.sql("""
					INSERT INTO accounts (id, email, password_hash, setup_slot, created_at, updated_at)
					VALUES (?, ?, ?, 1, ?, ?)""")
				.params(account.id(), account.email(), this.passwordEncoder.encode(password), now.toEpochMilli(),
						now.toEpochMilli())
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			// A concurrent setup took the slot first.
			throw new SetupAlreadyDoneException();
		}
		String projectId = this.projects.findOldestProjectId().orElseGet(() -> this.projects.createProject("Default"));
		this.projects.addMember(projectId, account.id(), "OWNER");
		return account;
	}

	/**
	 * The account with this email and password. Takes about as long whether or not the
	 * email exists.
	 */
	public Optional<Account> authenticate(String email, String password) {
		if (email == null || password == null || password.length() > MAX_PASSWORD_LENGTH) {
			this.passwordEncoder.matches("", this.dummyHash);
			return Optional.empty();
		}
		Optional<Stored> stored = this.jdbc
			.sql("SELECT id, email, password_hash, created_at FROM accounts WHERE email = ?")
			.param(email.strip().toLowerCase(Locale.ROOT))
			.query((rs, n) -> new Stored(
					new Account(rs.getString(1), rs.getString(2), Instant.ofEpochMilli(rs.getLong(4))),
					rs.getString(3)))
			.optional();
		if (stored.isEmpty()) {
			this.passwordEncoder.matches(password, this.dummyHash);
			return Optional.empty();
		}
		return this.passwordEncoder.matches(password, stored.get().passwordHash()) ? Optional.of(stored.get().account())
				: Optional.empty();
	}

	private record Stored(Account account, String passwordHash) {
	}

	public Optional<Account> findById(String id) {
		return this.jdbc.sql("SELECT id, email, created_at FROM accounts WHERE id = ?")
			.param(id)
			.query((rs, n) -> new Account(rs.getString(1), rs.getString(2), Instant.ofEpochMilli(rs.getLong(3))))
			.optional();
	}

	static String validateEmail(String email) {
		String normalized = (email != null) ? email.strip().toLowerCase(Locale.ROOT) : "";
		if (normalized.length() > 254 || !EMAIL.matcher(normalized).matches()) {
			throw new IllegalArgumentException("email must be a valid address");
		}
		return normalized;
	}

	static void validatePassword(String password) {
		if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
			throw new IllegalArgumentException(
					"password must be " + MIN_PASSWORD_LENGTH + " to " + MAX_PASSWORD_LENGTH + " characters");
		}
	}

}
