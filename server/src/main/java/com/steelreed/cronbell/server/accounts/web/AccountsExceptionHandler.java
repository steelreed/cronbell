package com.steelreed.cronbell.server.accounts.web;

import com.steelreed.cronbell.server.accounts.internal.SetupAlreadyDoneException;
import com.steelreed.cronbell.server.shared.web.ManagementApi;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Sign-in and setup errors of the management API, as RFC 9457 problem details. */
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

}
