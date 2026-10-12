package com.steelreed.cronbell.server.accounts.internal;

/** The first-run setup was attempted while an account already exists. */
public class SetupAlreadyDoneException extends RuntimeException {

	public SetupAlreadyDoneException() {
		super("setup is already done: sign in instead");
	}

}
