package com.steelreed.cronbell.server.accounts.web;

import com.steelreed.cronbell.server.accounts.internal.AccountConflictException;
import com.steelreed.cronbell.server.accounts.internal.SetupAlreadyDoneException;
import com.steelreed.cronbell.server.shared.web.ManagementApi;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errors of sign-in, setup, invites and memberships in the management API, as RFC 9457
 * problem details.
 */
@RestControllerAdvice(annotations = ManagementApi.class)
class AccountsExceptionHandler {

	@ExceptionHandler(NotSignedInException.class)
	ProblemDetail notSignedIn(NotSignedInException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
	}

	@ExceptionHandler(TooManySignInsException.class)
	ResponseEntity<ProblemDetail> tooManySignIns(TooManySignInsException ex) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage()));
	}

	@ExceptionHandler(SetupAlreadyDoneException.class)
	ProblemDetail setupDone(SetupAlreadyDoneException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
	}

	@ExceptionHandler(AccountConflictException.class)
	ProblemDetail conflict(AccountConflictException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
	}

	/** A role check in a controller, beyond what the security rules decide by path. */
	@ExceptionHandler(AccessDeniedException.class)
	ProblemDetail forbidden(AccessDeniedException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
	}

}
