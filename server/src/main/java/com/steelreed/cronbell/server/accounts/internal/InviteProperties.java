package com.steelreed.cronbell.server.accounts.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Invite settings ({@code cronbell.invites.*}, DESIGN.md §6, Projects and invites).
 *
 * @param ttl how long an invite link stays valid; must be positive
 */
@ConfigurationProperties("cronbell.invites")
public record InviteProperties(@DefaultValue("7d") Duration ttl) {

	public InviteProperties {
		if (ttl == null || ttl.isZero() || ttl.isNegative()) {
			throw new IllegalArgumentException(
					"cronbell.invites.ttl (CRONBELL_INVITE_TTL) must be a positive duration such as 7d, got " + ttl);
		}
	}

}
