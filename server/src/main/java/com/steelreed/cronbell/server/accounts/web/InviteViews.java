package com.steelreed.cronbell.server.accounts.web;

import java.time.Instant;

import com.steelreed.cronbell.server.accounts.internal.InviteService;
import com.steelreed.cronbell.server.accounts.internal.InviteService.Invite;
import com.steelreed.cronbell.server.shared.web.NotFoundException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Invites as the API returns them, and their links:
 * {@code <CRONBELL_BASE_URL>/invite/<token>}, or the request's own address when the base
 * URL is unset (DESIGN.md §6, Projects and invites).
 */
@Component
class InviteViews {

	private final String baseUrl;

	InviteViews(@Value("${cronbell.base-url:}") String baseUrl) {
		this.baseUrl = baseUrl.strip().replaceAll("/+$", "");
	}

	String link(String token) {
		String base = this.baseUrl.isEmpty() ? ServletUriComponentsBuilder.fromCurrentContextPath().toUriString()
				: this.baseUrl;
		return base + "/invite/" + token;
	}

	InviteResponse response(Invite invite) {
		return InviteResponse.of(invite, null);
	}

	InviteResponse response(InviteService.NewInvite created) {
		return InviteResponse.of(created.invite(), link(created.token()));
	}

	/**
	 * @param projectName the project joined, or the project made on acceptance (null
	 * before)
	 * @param url the link, only in the answer to its creation
	 */
	record InviteResponse(String id, InviteService.Kind kind, InviteService.Status status, String note,
			String projectName, String createdBy, Instant createdAt, Instant expiresAt, String acceptedBy,
			Instant acceptedAt, String url) {

		static InviteResponse of(Invite invite, String url) {
			return new InviteResponse(invite.id(), invite.kind(), invite.status(), invite.note(), invite.projectName(),
					invite.createdBy(), invite.createdAt(), invite.expiresAt(), invite.acceptedBy(),
					invite.acceptedAt(), url);
		}

	}

	record InviteRequest(String note) {
	}

	static class InviteNotFoundException extends NotFoundException {

		InviteNotFoundException(String id) {
			super("no invite " + id);
		}

	}

}
