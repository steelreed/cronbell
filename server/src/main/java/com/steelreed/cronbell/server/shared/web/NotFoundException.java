package com.steelreed.cronbell.server.shared.web;

/**
 * Something the caller asked for by slug or id does not exist in its project. The
 * management API answers {@code 404} with the message as detail.
 */
public abstract class NotFoundException extends RuntimeException {

	protected NotFoundException(String message) {
		super(message);
	}

}
