package com.steelreed.cronbell.server.checks.web;

import com.steelreed.cronbell.server.checks.CheckLimitReachedException;
import com.steelreed.cronbell.server.shared.web.ManagementApi;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errors only the checks API raises, as RFC 9457 problem details. */
@RestControllerAdvice(annotations = ManagementApi.class)
class ChecksExceptionHandler {

	@ExceptionHandler(CheckLimitReachedException.class)
	ProblemDetail limitReached(CheckLimitReachedException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
		problem.setTitle("Check limit reached");
		problem.setProperty("limit", ex.limit());
		return problem;
	}

}
