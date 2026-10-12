/**
 * Accounts: accounts and first-run setup, projects and their memberships, API keys, and
 * the security of the HTTP API (bearer keys, browser sessions, CSRF, sign-in backoff).
 * <p>
 * API: {@link com.steelreed.cronbell.server.accounts.ProjectPrincipal}, the caller every
 * controller acts for.
 */
@ApplicationModule(displayName = "Accounts", allowedDependencies = { "shared :: database", "shared :: web" })
package com.steelreed.cronbell.server.accounts;

import org.springframework.modulith.ApplicationModule;
