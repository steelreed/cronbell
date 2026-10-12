package com.steelreed.cronbell.server.accounts.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.steelreed.cronbell.server.accounts.internal.ProjectService.Role;
import com.steelreed.cronbell.server.shared.database.Ids;
import com.steelreed.cronbell.server.shared.web.NotFoundException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-use invite links (DESIGN.md §6, Projects and invites). A project invite gives
 * the invitee a new project of their own; a member invite adds them to an existing
 * project. Only the SHA-256 of a token is stored.
 */
@Service
public class InviteService {

	public static final int MAX_NOTE_LENGTH = 200;

	/** Tokens are 43 characters; anything much longer is not one. */
	private static final int MAX_TOKEN_LENGTH = 128;

	public enum Kind {

		/** From the instance admin: the invitee gets a new project they own. */
		PROJECT,

		/** From an owner: the invitee joins the owner's project as a member. */
		MEMBER

	}

	public enum Status {

		PENDING, ACCEPTED, EXPIRED

	}

	/**
	 * An invite as its creator sees it.
	 *
	 * @param projectId the project joined (member invite) or created on acceptance
	 * (project invite, null before)
	 * @param createdBy email of the inviter
	 * @param acceptedBy email of the account that accepted it, if any
	 */
	public record Invite(String id, Kind kind, String projectId, String projectName, String note, String createdBy,
			Instant createdAt, Instant expiresAt, String acceptedBy, Instant acceptedAt, Status status) {
	}

	/** An invite just made, with its token, shown once. */
	public record NewInvite(Invite invite, String token) {
	}

	/**
	 * What a valid link offers, for the page that accepts it.
	 *
	 * @param projectName the project a member invite joins; null for a project invite
	 * @param invitedBy email of the inviter
	 */
	public record Offer(Kind kind, String projectName, String invitedBy, Instant expiresAt) {
	}

	/** The account that accepted an invite and the project it now works in. */
	public record Accepted(Account account, String projectId) {
	}

	/** Unknown, used, deleted and expired links all look the same. */
	public static class InviteNotFoundException extends NotFoundException {

		public InviteNotFoundException() {
			super("this invite link is not valid: it may have expired, been used or been withdrawn");
		}

	}

	private record Pending(String id, Kind kind, String projectId) {
	}

	private final JdbcClient jdbc;

	private final AccountService accounts;

	private final ProjectService projects;

	private final InviteProperties properties;

	private final Clock clock;

	InviteService(JdbcClient jdbc, AccountService accounts, ProjectService projects, InviteProperties properties,
			Clock clock) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.projects = projects;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Makes an invite link.
	 * @param projectId the project to join, for a member invite; null for a project
	 * invite
	 * @param createdBy the inviting account
	 * @throws IllegalArgumentException if the note is too long
	 */
	@Transactional
	public NewInvite create(Kind kind, String projectId, String createdBy, String note) {
		if ((kind == Kind.MEMBER) != (projectId != null)) {
			throw new IllegalStateException("a member invite needs a project, a project invite none");
		}
		String cleanNote = (note != null && !note.isBlank()) ? note.strip() : null;
		if (cleanNote != null && cleanNote.length() > MAX_NOTE_LENGTH) {
			throw new IllegalArgumentException("note must be at most " + MAX_NOTE_LENGTH + " characters");
		}
		String token = ProjectService.randomToken();
		String id = Ids.newId();
		Instant now = now();
		this.jdbc.sql("""
				INSERT INTO invites (id, token_hash, kind, project_id, note, created_by, created_at, expires_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
			.params(id, ProjectService.hash(token), kind.name(), projectId, cleanNote, createdBy, now.toEpochMilli(),
					now.plus(this.properties.ttl()).toEpochMilli())
			.update();
		return new NewInvite(find(id).orElseThrow(), token);
	}

	/**
	 * Invites of one kind, newest first: the member invites of a project, or every
	 * project invite of the instance ({@code projectId} null).
	 */
	public List<Invite> list(Kind kind, String projectId) {
		String condition = (kind == Kind.MEMBER) ? "i.kind = 'MEMBER' AND i.project_id = :project"
				: "i.kind = 'PROJECT'";
		var spec = this.jdbc.sql(select(condition) + " ORDER BY i.created_at DESC, i.id DESC");
		if (kind == Kind.MEMBER) {
			spec = spec.param("project", projectId);
		}
		return spec.query(this::map).list();
	}

	/**
	 * Deletes an invite of that kind (and project, for a member invite). A pending link
	 * stops working; an accepted invite only leaves the list.
	 * @return whether there was such an invite
	 */
	@Transactional
	public boolean delete(Kind kind, String projectId, String id) {
		if (kind == Kind.MEMBER) {
			return this.jdbc.sql("DELETE FROM invites WHERE id = ? AND kind = 'MEMBER' AND project_id = ?")
				.params(id, projectId)
				.update() > 0;
		}
		return this.jdbc.sql("DELETE FROM invites WHERE id = ? AND kind = 'PROJECT'").param(id).update() > 0;
	}

	/** What the link offers, if it is valid: known, not used and not expired. */
	public Optional<Offer> findOffer(String token) {
		return findPending(token).map((pending) -> {
			Invite invite = find(pending.id()).orElseThrow();
			return new Offer(invite.kind(), (invite.kind() == Kind.MEMBER) ? invite.projectName() : null,
					invite.createdBy(), invite.expiresAt());
		});
	}

	/**
	 * Accepts an invite with a new account. Atomic: of concurrent accepts of one link,
	 * one wins and the others create nothing.
	 * @param projectName name of the new project for a project invite; null for the
	 * default
	 * @throws InviteNotFoundException if the link is not valid (any more)
	 * @throws AccountConflictException if the email already has an account
	 * @throws IllegalArgumentException if the email, password or project name is invalid
	 */
	@Transactional
	public Accepted acceptWithNewAccount(String token, String email, String password, String projectName) {
		Pending pending = findPending(token).orElseThrow(InviteNotFoundException::new);
		String normalized = AccountService.validateEmail(email);
		String name = (pending.kind() == Kind.PROJECT) ? projectName(projectName, normalized) : null;
		Account account = this.accounts.createAccount(normalized, password);
		return new Accepted(account, join(pending, account, name));
	}

	/**
	 * Accepts an invite for an account that exists (the one signed in).
	 * @return the project the account now works in
	 * @throws InviteNotFoundException if the link is not valid (any more)
	 * @throws AccountConflictException if the account is already a member of the project
	 */
	@Transactional
	public String acceptWithAccount(String token, Account account, String projectName) {
		Pending pending = findPending(token).orElseThrow(InviteNotFoundException::new);
		String name = (pending.kind() == Kind.PROJECT) ? projectName(projectName, account.email()) : null;
		return join(pending, account, name);
	}

	private String join(Pending pending, Account account, String projectName) {
		String projectId = switch (pending.kind()) {
			case PROJECT -> {
				String id = this.projects.createProject(projectName);
				this.projects.addMember(id, account.id(), Role.OWNER);
				yield id;
			}
			case MEMBER -> {
				if (this.projects.findMembership(account.id(), pending.projectId()).isPresent()) {
					throw new AccountConflictException("you are already a member of this project");
				}
				this.projects.addMember(pending.projectId(), account.id(), Role.MEMBER);
				yield pending.projectId();
			}
		};
		// The claim: only one transaction can move accepted_at from NULL. A concurrent
		// accept waits for the row (PostgreSQL) or the connection (SQLite), then finds
		// it taken and rolls back everything it did.
		int claimed = this.jdbc.sql("""
				UPDATE invites SET accepted_by = ?, accepted_at = ?, project_id = ?
				 WHERE id = ? AND accepted_at IS NULL AND expires_at > ?""")
			.params(account.id(), now().toEpochMilli(), projectId, pending.id(), now().toEpochMilli())
			.update();
		if (claimed != 1) {
			throw new InviteNotFoundException();
		}
		return projectId;
	}

	/** The given name, or "<local part>'s project". */
	static String projectName(String requested, String email) {
		if (requested != null && !requested.isBlank()) {
			return ProjectService.validateName(requested);
		}
		String local = email.substring(0, email.indexOf('@'));
		return ((local.length() > 100) ? local.substring(0, 100) : local) + "'s project";
	}

	private Optional<Pending> findPending(String token) {
		if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
			return Optional.empty();
		}
		return this.jdbc.sql("""
				SELECT id, kind, project_id FROM invites
				 WHERE token_hash = ? AND accepted_at IS NULL AND expires_at > ?""")
			.params(ProjectService.hash(token), now().toEpochMilli())
			.query((rs, row) -> new Pending(rs.getString(1), Kind.valueOf(rs.getString(2)), rs.getString(3)))
			.optional();
	}

	private Optional<Invite> find(String id) {
		return this.jdbc.sql(select("i.id = :id")).param("id", id).query(this::map).optional();
	}

	private static String select(String condition) {
		return """
				SELECT i.id, i.kind, i.project_id, p.name AS project_name, i.note, c.email AS created_by,
				       i.created_at, i.expires_at, a.email AS accepted_by, i.accepted_at
				  FROM invites i
				  JOIN accounts c ON c.id = i.created_by
				  LEFT JOIN accounts a ON a.id = i.accepted_by
				  LEFT JOIN projects p ON p.id = i.project_id
				 WHERE %s""".formatted(condition);
	}

	private Invite map(ResultSet rs, int row) throws SQLException {
		Instant expiresAt = Instant.ofEpochMilli(rs.getLong("expires_at"));
		long acceptedMillis = rs.getLong("accepted_at");
		Instant acceptedAt = rs.wasNull() ? null : Instant.ofEpochMilli(acceptedMillis);
		Status status = (acceptedAt != null) ? Status.ACCEPTED
				: expiresAt.isAfter(now()) ? Status.PENDING : Status.EXPIRED;
		return new Invite(rs.getString("id"), Kind.valueOf(rs.getString("kind")), rs.getString("project_id"),
				rs.getString("project_name"), rs.getString("note"), rs.getString("created_by"),
				Instant.ofEpochMilli(rs.getLong("created_at")), expiresAt, rs.getString("accepted_by"), acceptedAt,
				status);
	}

	private Instant now() {
		return Instant.ofEpochMilli(this.clock.millis());
	}

}
