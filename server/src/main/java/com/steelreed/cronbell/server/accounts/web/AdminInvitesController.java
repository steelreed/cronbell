package com.steelreed.cronbell.server.accounts.web;

import java.util.List;

import com.steelreed.cronbell.server.accounts.ProjectPrincipal;
import com.steelreed.cronbell.server.accounts.internal.InviteService;
import com.steelreed.cronbell.server.accounts.web.InviteViews.InviteNotFoundException;
import com.steelreed.cronbell.server.accounts.web.InviteViews.InviteRequest;
import com.steelreed.cronbell.server.accounts.web.InviteViews.InviteResponse;
import com.steelreed.cronbell.server.shared.web.ManagementApi;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project invites of the instance (DESIGN.md §6, Projects and invites): each gives the
 * invitee a new project of their own. Instance admin only, enforced by the security
 * rules.
 */
@RestController
@ManagementApi
@RequestMapping("/api/v1/admin/invites")
class AdminInvitesController {

	private final InviteService invites;

	private final InviteViews views;

	AdminInvitesController(InviteService invites, InviteViews views) {
		this.invites = invites;
		this.views = views;
	}

	@GetMapping
	List<InviteResponse> list() {
		return this.invites.list(InviteService.Kind.PROJECT, null).stream().map(this.views::response).toList();
	}

	/** A new link, returned this once. */
	@PostMapping
	ResponseEntity<InviteResponse> create(@AuthenticationPrincipal ProjectPrincipal principal,
			@RequestBody(required = false) InviteRequest request) {
		var created = this.invites.create(InviteService.Kind.PROJECT, null, principal.accountId(),
				(request != null) ? request.note() : null);
		return ResponseEntity.status(HttpStatus.CREATED).body(this.views.response(created));
	}

	@DeleteMapping("/{id}")
	ResponseEntity<Void> delete(@PathVariable String id) {
		if (!this.invites.delete(InviteService.Kind.PROJECT, null, id)) {
			throw new InviteNotFoundException(id);
		}
		return ResponseEntity.noContent().build();
	}

}
