package com.steelreed.cronbell.server.api;

import com.steelreed.cronbell.server.account.Account;
import com.steelreed.cronbell.server.account.AccountService;
import com.steelreed.cronbell.server.project.ProjectService;
import com.steelreed.cronbell.server.security.LoginRateLimiter;
import com.steelreed.cronbell.server.security.ProjectPrincipal;
import com.steelreed.cronbell.server.security.SessionSignIn;
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

/** Browser sign-in and sign-out (DESIGN.md §6, Accounts and sessions). */
@RestController
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

	/** The signed-in account, or 401. A bearer API key is not a session. */
	@GetMapping
	SessionResponse get(@AuthenticationPrincipal ProjectPrincipal principal) {
		if (principal == null || !principal.isAccount()) {
			throw new NotSignedInException("not signed in");
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

	SessionResponse response(ProjectPrincipal principal) {
		String projectName = this.projects.findMemberships(principal.accountId())
			.stream()
			.filter((membership) -> membership.projectId().equals(principal.projectId()))
			.findFirst()
			.map(ProjectService.Membership::projectName)
			.orElse(null);
		String pingBaseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().path("/ping/").toUriString();
		return new SessionResponse(new AccountView(principal.accountId(), principal.email()),
				new ProjectView(principal.projectId(), projectName), pingBaseUrl);
	}

	record Credentials(String email, String password) {
	}

	record AccountView(String id, String email) {
	}

	record ProjectView(String id, String name) {
	}

	record SessionResponse(AccountView account, ProjectView project, String pingBaseUrl) {
	}

}
