package com.steelreed.cronbell.server.api;

import com.steelreed.cronbell.server.account.Account;
import com.steelreed.cronbell.server.account.AccountService;
import com.steelreed.cronbell.server.security.SessionSignIn;
import com.steelreed.cronbell.server.shared.web.ManagementApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One-time setup (DESIGN.md §6): while no account exists, anyone who reaches the server
 * can create the admin account. Afterwards it answers 409.
 */
@RestController
@ManagementApi
@RequestMapping("/api/v1/setup")
class SetupController {

	private final AccountService accounts;

	private final SessionSignIn sessions;

	private final SessionController sessionController;

	SetupController(AccountService accounts, SessionSignIn sessions, SessionController sessionController) {
		this.accounts = accounts;
		this.sessions = sessions;
		this.sessionController = sessionController;
	}

	@GetMapping
	SetupStatus status() {
		return new SetupStatus(!this.accounts.anyAccountExists());
	}

	/** Creates the admin account and signs it in. */
	@PostMapping
	ResponseEntity<SessionController.SessionResponse> setUp(@RequestBody SessionController.Credentials credentials,
			HttpServletRequest request, HttpServletResponse response) {
		Account account = this.accounts.createFirstAccount(credentials.email(), credentials.password());
		var principal = this.sessions.signIn(account, request, response);
		return ResponseEntity.status(HttpStatus.CREATED).body(this.sessionController.response(principal));
	}

	record SetupStatus(boolean required) {
	}

}
