package com.steelreed.cronbell.server.security;

import java.io.Serializable;

/**
 * The caller of the management API: a project, reached either with one of its API keys or
 * through the signed-in account that is a member of it.
 *
 * @param projectId the project the request acts on
 * @param accountId the signed-in account, or {@code null} for an API key
 * @param email the signed-in account's email, or {@code null} for an API key
 */
public record ProjectPrincipal(String projectId, String accountId, String email) implements Serializable {

	public static ProjectPrincipal apiKey(String projectId) {
		return new ProjectPrincipal(projectId, null, null);
	}

	public boolean isAccount() {
		return this.accountId != null;
	}

}
