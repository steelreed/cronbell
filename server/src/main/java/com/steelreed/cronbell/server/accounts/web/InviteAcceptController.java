package com.steelreed.cronbell.server.accounts.web;

import java.time.Instant;

import com.steelreed.cronbell.server.accounts.ProjectPrincipal;
import com.steelreed.cronbell.server.accounts.internal.Account;
import com.steelreed.cronbell.server.accounts.internal.AccountService;
import com.steelreed.cronbell.server.accounts.internal.InviteService;
import com.steelreed.cronbell.server.accounts.internal.InviteService.InviteNotFoundException;
import com.steelreed.cronbell.server.accounts.internal.security.LoginRateLimiter;
import com.steelreed.cronbell.server.accounts.internal.security.SessionSignIn;
import com.steelreed.cronbell.server.shared.web.ManagementApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public side of an invite link (DESIGN.md §6, Projects and invites): what it offers,
 * and accepting it with a new account or the signed-in one. Unknown links count against
 * the client address like failed sign-ins.
 */
@RestController
@ManagementApi
@RequestMapping("/api/v1/invites/{token}")
class InviteAcceptController {

	/** The rate limiter's key for invite links, apart from any email. */
	private static final String LIMITER_SUBJECT = "#invite";

	private final InviteService invites;

	private final AccountService accounts;

	private final SessionSignIn sessions;

	private final SessionController sessionController;

	private final LoginRateLimiter limiter;

	InviteAcceptController(InviteService invites, AccountService accounts, SessionSignIn sessions,
			SessionController sessionController, LoginRateLimiter limiter) {
		this.invites = invites;
		this.accounts = accounts;
		this.sessions = sessions;
		this.sessionController = sessionController;
		this.limiter = limiter;
	}

	@GetMapping
	OfferResponse offer(@PathVariable String token, HttpServletRequest request) {
		String client = checkLimit(request);
		var offer = this.invites.findOffer(token).orElseThrow(() -> notFound(client));
		return new OfferResponse(offer.kind(), offer.projectName(), offer.invitedBy(), offer.expiresAt());
	}

	/**
	 * 201 with the session of a new account, or 200 when the signed-in account accepted
	 * it.
	 */
	@PostMapping("/accept")
	ResponseEntity<SessionController.SessionResponse> accept(@PathVariable String token,
			@AuthenticationPrincipal ProjectPrincipal principal, @RequestBody(required = false) AcceptRequest body,
			HttpServletRequest request, HttpServletResponse response) {
		String client = checkLimit(request);
		AcceptRequest accept = (body != null) ? body : new AcceptRequest(null, null, null);
		try {
			if (principal != null && principal.isAccount()) {
				Account account = this.accounts.findById(principal.accountId()).orElseThrow();
				String projectId = this.invites.acceptWithAccount(token, account, accept.projectName());
				var signedIn = this.sessions.signInAgain(account, projectId, request, response);
				return ResponseEntity.ok(this.sessionController.response(signedIn));
			}
			var accepted = this.invites.acceptWithNewAccount(token, accept.email(), accept.password(),
					accept.projectName());
			var signedIn = this.sessions.signIn(accepted.account(), accepted.projectId(), request, response);
			return ResponseEntity.status(HttpStatus.CREATED).body(this.sessionController.response(signedIn));
		}
		catch (InviteNotFoundException ex) {
			throw notFound(client);
		}
	}

	private String checkLimit(HttpServletRequest request) {
		String client = request.getRemoteAddr();
		this.limiter.retryAfter(client, LIMITER_SUBJECT).ifPresent((wait) -> {
			throw new TooManySignInsException(wait);
		});
		return client;
	}

	private InviteNotFoundException notFound(String client) {
		this.limiter.failed(client, LIMITER_SUBJECT);
		return new InviteNotFoundException();
	}

	/**
	 * The body of an accept.
	 *
	 * @param email and {@code password}: the new account; ignored when signed in
	 * @param projectName name of the new project of a project invite (optional)
	 */
	record AcceptRequest(String email, String password, String projectName) {
	}

	/**
	 * What a link offers.
	 *
	 * @param projectName the project a member invite joins; null for a project invite
	 */
	record OfferResponse(InviteService.Kind kind, String projectName, String invitedBy, Instant expiresAt) {
	}

}
