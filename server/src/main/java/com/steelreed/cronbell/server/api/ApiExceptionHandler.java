package com.steelreed.cronbell.server.api;

import com.steelreed.cronbell.server.account.SetupAlreadyDoneException;
import com.steelreed.cronbell.server.check.CheckNotFoundException;
import com.steelreed.cronbell.server.notify.ChannelNotFoundException;
import com.steelreed.cronbell.server.notify.ChannelService;
import com.steelreed.cronbell.server.notify.DeliveryException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errors of the management API as RFC 9457 problem details. */
@RestControllerAdvice(assignableTypes = { ChecksController.class, ChannelsController.class, SessionController.class,
		SetupController.class, KeysController.class })
class ApiExceptionHandler {

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail badRequest(IllegalArgumentException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(CheckNotFoundException.class)
	ProblemDetail notFound(CheckNotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(ChannelNotFoundException.class)
	ProblemDetail notFound(ChannelNotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(KeysController.KeyNotFoundException.class)
	ProblemDetail notFound(KeysController.KeyNotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

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

	@ExceptionHandler(ChannelService.TooManyTestsException.class)
	ProblemDetail tooManyTests(ChannelService.TooManyTestsException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
	}

	/** The channel refused a test message. The detail never holds a secret. */
	@ExceptionHandler(DeliveryException.class)
	ProblemDetail deliveryFailed(DeliveryException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
		problem.setTitle("Test message not delivered");
		return problem;
	}

	/** Two concurrent creates of the same slug: the client can retry. */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ProblemDetail conflict(DataIntegrityViolationException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "concurrent update, retry the request");
	}

}
