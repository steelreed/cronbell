package com.steelreed.cronbell.server.api;

/** The request has no signed-in session. */
class NotSignedInException extends RuntimeException {

	NotSignedInException(String message) {
		super(message);
	}

}
