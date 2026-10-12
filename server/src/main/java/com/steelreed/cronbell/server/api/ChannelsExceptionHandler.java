package com.steelreed.cronbell.server.api;

import com.steelreed.cronbell.server.notify.ChannelService;
import com.steelreed.cronbell.server.notify.DeliveryException;
import com.steelreed.cronbell.server.shared.web.ManagementApi;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errors of channel tests, as RFC 9457 problem details. */
@RestControllerAdvice(annotations = ManagementApi.class)
class ChannelsExceptionHandler {

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

}
