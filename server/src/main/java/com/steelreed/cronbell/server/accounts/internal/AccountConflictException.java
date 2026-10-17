package com.steelreed.cronbell.server.accounts.internal;

/**
 * The request conflicts with the accounts and memberships as they are: an email that
 * already has an account, a membership that exists, the last owner of a project. The
 * management API answers {@code 409} with the message as detail.
 */
public class AccountConflictException extends RuntimeException {

	public AccountConflictException(String message) {
		super(message);
	}

}
