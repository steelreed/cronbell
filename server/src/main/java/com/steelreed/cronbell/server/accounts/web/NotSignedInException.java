package com.steelreed.cronbell.server.accounts.web;

/** The request has no signed-in session. */
class NotSignedInException extends RuntimeException {

	NotSignedInException(String message) {
		super(message);
	}

}
