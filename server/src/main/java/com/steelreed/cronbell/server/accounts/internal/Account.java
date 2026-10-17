package com.steelreed.cronbell.server.accounts.internal;

import java.time.Instant;

/**
 * A person who signs in. {@code email} is lower-case.
 *
 * @param instanceAdmin whether this is the account made by the setup, which may invite
 * people to projects of their own
 * @param sessionVersion raised when the password changes; sessions made with an older
 * version are signed out
 */
public record Account(String id, String email, boolean instanceAdmin, int sessionVersion, Instant createdAt) {
}
