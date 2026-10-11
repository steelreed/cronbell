package com.steelreed.cronbell.server.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.steelreed.cronbell.server.notify.Channel;
import com.steelreed.cronbell.server.notify.ChannelConfigs;
import com.steelreed.cronbell.server.notify.ChannelService;
import com.steelreed.cronbell.server.notify.DeliveryException;
import com.steelreed.cronbell.server.notify.DeliveryRepository;
import com.steelreed.cronbell.server.security.ProjectPrincipal;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Management API for alert channels (DESIGN.md §6, §8). Scoped to the project of the API
 * key. Secrets in the config are returned masked.
 */
@RestController
@RequestMapping("/api/v1/channels")
class ChannelsController {

	private final ChannelService channels;

	ChannelsController(ChannelService channels) {
		this.channels = channels;
	}

	@GetMapping
	List<ChannelResponse> list(@AuthenticationPrincipal ProjectPrincipal project) {
		return this.channels.list(project.projectId()).stream().map(ChannelResponse::of).toList();
	}

	@PostMapping
	ResponseEntity<ChannelResponse> create(@AuthenticationPrincipal ProjectPrincipal project,
			@RequestBody ChannelRequest request) {
		Channel channel = this.channels.create(project.projectId(), request.type(), request.name(), request.config());
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
			.path("/{id}")
			.buildAndExpand(channel.id())
			.toUri();
		return ResponseEntity.created(location)
			.body(ChannelResponse.of(channel, DeliveryRepository.ChannelStatus.NONE));
	}

	@GetMapping("/{id}")
	ChannelResponse get(@AuthenticationPrincipal ProjectPrincipal project, @PathVariable String id) {
		return ChannelResponse.of(this.channels.get(project.projectId(), id));
	}

	@PutMapping("/{id}")
	ChannelResponse update(@AuthenticationPrincipal ProjectPrincipal project, @PathVariable String id,
			@RequestBody ChannelRequest request) {
		this.channels.update(project.projectId(), id, request.type(), request.name(), request.config());
		return ChannelResponse.of(this.channels.get(project.projectId(), id));
	}

	@DeleteMapping("/{id}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal ProjectPrincipal project, @PathVariable String id) {
		this.channels.delete(project.projectId(), id);
		return ResponseEntity.noContent().build();
	}

	/** Sends a test message now. 200 when the channel accepted it, else 502. */
	@PostMapping("/{id}/test")
	TestResponse test(@AuthenticationPrincipal ProjectPrincipal project, @PathVariable String id)
			throws DeliveryException {
		this.channels.test(project.projectId(), id);
		return new TestResponse(true);
	}

	record ChannelRequest(String type, String name, Map<String, Object> config) {
	}

	record ChannelResponse(String id, String type, String name, Map<String, String> config, Instant createdAt,
			Instant updatedAt, Instant lastAttemptAt, Instant lastDeliveryAt, String lastError, int pendingDeliveries) {

		static ChannelResponse of(ChannelService.View view) {
			return of(view.channel(), view.status());
		}

		static ChannelResponse of(Channel c, DeliveryRepository.ChannelStatus s) {
			return new ChannelResponse(c.id(), c.type().id(), c.name(), ChannelConfigs.masked(c), c.createdAt(),
					c.updatedAt(), s.lastAttemptAt(), s.lastDeliveryAt(), s.lastError(), s.pending());
		}

	}

	record TestResponse(boolean delivered) {
	}

}
