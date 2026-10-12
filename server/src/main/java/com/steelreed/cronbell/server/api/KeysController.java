package com.steelreed.cronbell.server.api;

import java.time.Instant;
import java.util.List;

import com.steelreed.cronbell.server.project.ProjectService;
import com.steelreed.cronbell.server.security.ProjectPrincipal;
import com.steelreed.cronbell.server.shared.web.ManagementApi;
import com.steelreed.cronbell.server.shared.web.NotFoundException;

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
 * API keys of the signed-in account's project (DESIGN.md §6). Session only: an API key
 * cannot manage keys. A new key is returned once; only its hash is stored.
 */
@RestController
@ManagementApi
@RequestMapping("/api/v1/keys")
class KeysController {

	private final ProjectService projects;

	KeysController(ProjectService projects) {
		this.projects = projects;
	}

	@GetMapping
	List<KeyResponse> list(@AuthenticationPrincipal ProjectPrincipal project) {
		return this.projects.listApiKeys(project.projectId()).stream().map(KeyResponse::of).toList();
	}

	@PostMapping
	ResponseEntity<NewKeyResponse> create(@AuthenticationPrincipal ProjectPrincipal project,
			@RequestBody(required = false) KeyRequest request) {
		var created = this.projects.createApiKeyWithId(project.projectId(), (request != null) ? request.name() : null);
		var key = created.apiKey();
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(new NewKeyResponse(key.id(), key.name(), key.prefix(), key.createdAt(), created.key()));
	}

	@DeleteMapping("/{id}")
	ResponseEntity<Void> revoke(@AuthenticationPrincipal ProjectPrincipal project, @PathVariable String id) {
		if (!this.projects.revokeApiKey(project.projectId(), id)) {
			throw new KeyNotFoundException(id);
		}
		return ResponseEntity.noContent().build();
	}

	record KeyRequest(String name) {
	}

	record KeyResponse(String id, String name, String prefix, Instant createdAt) {

		static KeyResponse of(ProjectService.ApiKey key) {
			return new KeyResponse(key.id(), key.name(), key.prefix(), key.createdAt());
		}

	}

	record NewKeyResponse(String id, String name, String prefix, Instant createdAt, String key) {
	}

	static class KeyNotFoundException extends NotFoundException {

		KeyNotFoundException(String id) {
			super("no API key " + id);
		}

	}

}
