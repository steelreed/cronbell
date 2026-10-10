package com.steelreed.cronbell.server.security;

/** The caller of the management API: a project, identified by one of its API keys. */
public record ProjectPrincipal(String projectId) {
}
