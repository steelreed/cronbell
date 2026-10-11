package com.steelreed.cronbell.server.account;

import java.time.Instant;

/** A person who signs in. {@code email} is lower-case. */
public record Account(String id, String email, Instant createdAt) {
}
