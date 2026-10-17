package com.steelreed.cronbell.server.checks;

/**
 * A new check would take the project over {@code CRONBELL_PROJECT_MAX_CHECKS}. The
 * management API answers {@code 403}, as Healthchecks.io does.
 */
public class CheckLimitReachedException extends RuntimeException {

	private final int limit;

	public CheckLimitReachedException(int limit) {
		super("this project has reached its limit of " + limit + " checks");
		this.limit = limit;
	}

	public int limit() {
		return this.limit;
	}

}
