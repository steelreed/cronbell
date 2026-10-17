package com.steelreed.cronbell.server.accounts.web;

import java.time.Instant;
import java.util.List;

import com.steelreed.cronbell.server.accounts.ProjectPrincipal;
import com.steelreed.cronbell.server.accounts.internal.ProjectService;
import com.steelreed.cronbell.server.accounts.internal.ProjectService.Role;
import com.steelreed.cronbell.server.shared.web.ManagementApi;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Members of the current project (DESIGN.md §6, Roles): every member can list them; an
 * owner removes anyone but the last owner, a member only themself (leaving).
 */
@RestController
@ManagementApi
@RequestMapping("/api/v1/members")
class MembersController {

	private final ProjectService projects;

	MembersController(ProjectService projects) {
		this.projects = projects;
	}

	@GetMapping
	List<MemberResponse> list(@AuthenticationPrincipal ProjectPrincipal principal) {
		return this.projects.listMembers(principal.projectId())
			.stream()
			.map((member) -> new MemberResponse(member.accountId(), member.email(), member.role(), member.joinedAt(),
					member.accountId().equals(principal.accountId())))
			.toList();
	}

	@DeleteMapping("/{accountId}")
	ResponseEntity<Void> remove(@AuthenticationPrincipal ProjectPrincipal principal, @PathVariable String accountId) {
		boolean self = accountId.equals(principal.accountId());
		if (!self) {
			Role role = this.projects.findMembership(principal.accountId(), principal.projectId())
				.map(ProjectService.Membership::role)
				.orElse(null);
			if (role != Role.OWNER) {
				// An account outside the project is 404, as for an owner.
				if (this.projects.listMembers(principal.projectId())
					.stream()
					.noneMatch((member) -> member.accountId().equals(accountId))) {
					throw new ProjectService.MemberNotFoundException(accountId);
				}
				throw new AccessDeniedException("only an owner can remove other members");
			}
		}
		this.projects.removeMember(principal.projectId(), accountId);
		return ResponseEntity.noContent().build();
	}

	/**
	 * A member of the project.
	 *
	 * @param you whether this is the signed-in account
	 */
	record MemberResponse(String accountId, String email, Role role, Instant joinedAt, boolean you) {
	}

}
