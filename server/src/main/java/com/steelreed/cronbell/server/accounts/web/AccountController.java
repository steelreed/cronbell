package com.steelreed.cronbell.server.accounts.web;

import java.util.List;

import com.steelreed.cronbell.server.accounts.ProjectPrincipal;
import com.steelreed.cronbell.server.accounts.internal.Account;
import com.steelreed.cronbell.server.accounts.internal.AccountService;
import com.steelreed.cronbell.server.accounts.internal.ProjectService;
import com.steelreed.cronbell.server.accounts.internal.security.LoginRateLimiter;
import com.steelreed.cronbell.server.accounts.internal.security.SessionSignIn;
import com.steelreed.cronbell.server.shared.web.ManagementApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in account's own settings and projects (DESIGN.md §6): its projects with its
 * role, and its password. Sessions only.
 */
@RestController
@ManagementApi
class AccountController {

	private final AccountService accounts;

	private final ProjectService projects;

	private final SessionSignIn sessions;

	private final LoginRateLimiter limiter;

	AccountController(AccountService accounts, ProjectService projects, SessionSignIn sessions,
			LoginRateLimiter limiter) {
		this.accounts = accounts;
		this.projects = projects;
		this.sessions = sessions;
		this.limiter = limiter;
	}

	/** Projects of the account, oldest membership first. */
	@GetMapping("/api/v1/projects")
	List<SessionController.ProjectView> projects(@AuthenticationPrincipal ProjectPrincipal principal) {
		return this.projects.findMemberships(principal.accountId())
			.stream()
			.map(SessionController.ProjectView::of)
			.toList();
	}

	/**
	 * Changes the password. Wrong current passwords count like failed sign-ins. Every
	 * other session of the account ends; this one goes on with a new session id. A
	 * concurrent change with the same current password is a 409.
	 */
	@PostMapping("/api/v1/account/password")
	ResponseEntity<Void> changePassword(@AuthenticationPrincipal ProjectPrincipal principal,
			@RequestBody PasswordChange change, HttpServletRequest request, HttpServletResponse response) {
		String client = request.getRemoteAddr();
		this.limiter.retryAfter(client, principal.email()).ifPresent((wait) -> {
			throw new TooManySignInsException(wait);
		});
		Account account = this.accounts.authenticateById(principal.accountId(), change.currentPassword())
			.orElseThrow(() -> {
				this.limiter.failed(client, principal.email());
				return new IllegalArgumentException("the current password is wrong");
			});
		this.limiter.succeeded(client, principal.email());
		Account changed = this.accounts.changePassword(account, change.newPassword());
		this.sessions.signIn(changed, principal.projectId(), request, response);
		return ResponseEntity.noContent().build();
	}

	record PasswordChange(String currentPassword, String newPassword) {
	}

}
