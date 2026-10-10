package com.steelreed.cronbell.server.api;

import com.steelreed.cronbell.server.check.CheckNotFoundException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errors of the management API as RFC 9457 problem details. */
@RestControllerAdvice(assignableTypes = ChecksController.class)
class ApiExceptionHandler {

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail badRequest(IllegalArgumentException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(CheckNotFoundException.class)
	ProblemDetail notFound(CheckNotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	/** Two concurrent creates of the same slug: the client can retry. */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ProblemDetail conflict(DataIntegrityViolationException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "concurrent update, retry the request");
	}

}
