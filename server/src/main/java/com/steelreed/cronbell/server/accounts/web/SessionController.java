package com.steelreed.cronbell.server.accounts.web;

import java.util.List;

import com.steelreed.cronbell.server.accounts.ProjectPrincipal;
import com.steelreed.cronbell.server.accounts.internal.Account;
import com.steelreed.cronbell.server.accounts.internal.AccountService;
import com.steelreed.cronbell.server.accounts.internal.ProjectService;
import com.steelreed.cronbell.server.accounts.internal.ProjectService.Membership;
import com.steelreed.cronbell.server.accounts.internal.ProjectService.Role;
import com.steelreed.cronbell.server.accounts.internal.security.LoginRateLimiter;
import com.steelreed.cronbell.server.accounts.internal.security.SessionSignIn;
import com.steelreed.cronbell.server.shared.web.ManagementApi;
import com.steelreed.cronbell.server.shared.web.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Browser sign-in and sign-out, and the session's current project (DESIGN.md §6, Accounts
 * and sessions; Projects and invites).
 */
@RestController
@ManagementApi
@RequestMapping("/api/v1/session")
class SessionController {

	private final AccountService accounts;

	private final ProjectService projects;

	private final SessionSignIn sessions;

	private final LoginRateLimiter limiter;

	SessionController(AccountService accounts, ProjectService projects, SessionSignIn sessions,
			LoginRateLimiter limiter) {
		this.accounts = accounts;
		this.projects = projects;
		this.sessions = sessions;
		this.limiter = limiter;
	}

	/**
	 * The signed-in account, or 401. A bearer API key is not a session. When the current
	 * project is no longer one of the account's, the session moves to the oldest one.
	 */
	@GetMapping
	SessionResponse get(@AuthenticationPrincipal ProjectPrincipal principal, HttpServletRequest request,
			HttpServletResponse response) {
		if (principal == null || !principal.isAccount()) {
			throw new NotSignedInException("not signed in");
		}
		if (principal.projectId() == null) {
			Account account = this.accounts.findById(principal.accountId()).orElseThrow();
			List<Membership> memberships = this.projects.findMemberships(account.id());
			if (!memberships.isEmpty()) {
				return response(this.sessions.switchProject(principal, memberships.getFirst(), account.instanceAdmin(),
						request, response));
			}
		}
		return response(principal);
	}

	@PostMapping
	SessionResponse signIn(@RequestBody Credentials credentials, HttpServletRequest request,
			HttpServletResponse response) {
		String client = request.getRemoteAddr();
		this.limiter.retryAfter(client, credentials.email()).ifPresent((wait) -> {
			throw new TooManySignInsException(wait);
		});
		Account account = this.accounts.authenticate(credentials.email(), credentials.password()).orElse(null);
		if (account == null) {
			this.limiter.failed(client, credentials.email());
			throw new NotSignedInException("wrong email or password");
		}
		this.limiter.succeeded(client, credentials.email());
		return response(this.sessions.signIn(account, request, response));
	}

	@DeleteMapping
	ResponseEntity<Void> signOut(HttpServletRequest request, HttpServletResponse response) {
		this.sessions.signOut(request, response);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Makes another project of the account the current one. 404 for a project it is not a
	 * member of, whether or not it exists.
	 */
	@PostMapping("/project")
	SessionResponse switchProject(@AuthenticationPrincipal ProjectPrincipal principal,
			@RequestBody ProjectChoice choice, HttpServletRequest request, HttpServletResponse response) {
		String projectId = (choice != null) ? choice.projectId() : null;
		if (projectId == null || projectId.isBlank()) {
			throw new IllegalArgumentException("projectId is required");
		}
		Membership membership = this.projects.findMembership(principal.accountId(), projectId)
			.orElseThrow(() -> new ProjectNotFoundException(projectId));
		Account account = this.accounts.findById(principal.accountId()).orElseThrow();
		return response(this.sessions.switchProject(principal, membership, account.instanceAdmin(), request, response));
	}

	SessionResponse response(ProjectPrincipal principal) {
		Account account = this.accounts.findById(principal.accountId()).orElseThrow();
		List<ProjectView> projects = this.projects.findMemberships(account.id()).stream().map(ProjectView::of).toList();
		ProjectView current = projects.stream()
			.filter((project) -> project.id().equals(principal.projectId()))
			.findFirst()
			.orElse(null);
		String pingBaseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().path("/ping/").toUriString();
		return new SessionResponse(new AccountView(account.id(), account.email(), account.instanceAdmin()), current,
				projects, pingBaseUrl);
	}

	record Credentials(String email, String password) {
	}

	record ProjectChoice(String projectId) {
	}

	record AccountView(String id, String email, boolean instanceAdmin) {
	}

	record ProjectView(String id, String name, Role role) {

		static ProjectView of(Membership membership) {
			return new ProjectView(membership.projectId(), membership.projectName(), membership.role());
		}

	}

	/**
	 * @param project the current project, or null when the account has none
	 * @param projects every project of the account, oldest membership first
	 */
	record SessionResponse(AccountView account, ProjectView project, List<ProjectView> projects, String pingBaseUrl) {
	}

	static class ProjectNotFoundException extends NotFoundException {

		ProjectNotFoundException(String id) {
			super("no project " + id);
		}

	}

}
