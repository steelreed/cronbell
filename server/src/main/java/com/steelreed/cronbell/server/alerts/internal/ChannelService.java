package com.steelreed.cronbell.server.alerts.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.steelreed.cronbell.server.shared.database.Ids;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Alert channels of a project (DESIGN.md §8). */
@Service
public class ChannelService {

	/** One test message per channel per this interval. */
	public static final Duration TEST_INTERVAL = Duration.ofSeconds(10);

	/** A channel with its recent delivery status. */
	public record View(Channel channel, DeliveryRepository.ChannelStatus status) {
	}

	/** Thrown when a channel is tested again too soon. */
	public static class TooManyTestsException extends RuntimeException {

		public TooManyTestsException() {
			super("a test message was sent less than " + TEST_INTERVAL.toSeconds() + " seconds ago");
		}

	}

	private final ChannelRepository channels;

	private final DeliveryRepository deliveries;

	private final Senders senders;

	private final EmailSender email;

	private final Clock clock;

	private final Map<String, Instant> lastTests = new ConcurrentHashMap<>();

	ChannelService(ChannelRepository channels, DeliveryRepository deliveries, Senders senders, EmailSender email,
			Clock clock) {
		this.channels = channels;
		this.deliveries = deliveries;
		this.senders = senders;
		this.email = email;
		this.clock = clock;
	}

	public List<View> list(String projectId) {
		Map<String, DeliveryRepository.ChannelStatus> status = this.deliveries.statusByChannel(projectId);
		return this.channels.findAllByProject(projectId)
			.stream()
			.map((channel) -> new View(channel,
					status.getOrDefault(channel.id(), DeliveryRepository.ChannelStatus.NONE)))
			.toList();
	}

	public View get(String projectId, String id) {
		Channel channel = find(projectId, id);
		return new View(channel, this.deliveries.statusByChannel(projectId)
			.getOrDefault(channel.id(), DeliveryRepository.ChannelStatus.NONE));
	}

	/**
	 * @throws IllegalArgumentException if the type, name or config is invalid
	 */
	@Transactional
	public Channel create(String projectId, String type, String name, Map<String, ?> config) {
		ChannelType channelType = ChannelType.of(type);
		requireEmailAvailable(channelType);
		Map<String, String> validated = ChannelConfigs.validate(channelType, config, null);
		Instant now = now();
		Channel channel = new Channel(Ids.newId(), projectId, channelType, name(name, channelType), validated, now,
				now);
		this.channels.insert(channel);
		return channel;
	}

	/**
	 * Updates name and config. Secret fields left out (or sent back masked) keep their
	 * value. The type cannot change.
	 */
	@Transactional
	public Channel update(String projectId, String id, String type, String name, Map<String, ?> config) {
		Channel current = find(projectId, id);
		if (type != null && ChannelType.of(type) != current.type()) {
			throw new IllegalArgumentException("the type of a channel cannot change");
		}
		Map<String, String> validated = ChannelConfigs.validate(current.type(), config, current.config());
		Channel updated = new Channel(current.id(), projectId, current.type(), name(name, current.type()), validated,
				current.createdAt(), now());
		this.channels.update(updated);
		return updated;
	}

	public void delete(String projectId, String id) {
		if (!this.channels.delete(projectId, id)) {
			throw new ChannelNotFoundException(id);
		}
	}

	/**
	 * Sends a test message now, outside the outbox.
	 * @throws DeliveryException if the channel did not accept it
	 * @throws TooManyTestsException if the channel was tested less than
	 * {@link #TEST_INTERVAL} ago
	 */
	public void test(String projectId, String id) throws DeliveryException {
		Channel channel = find(projectId, id);
		Instant now = now();
		AtomicBoolean allowed = new AtomicBoolean();
		this.lastTests.compute(channel.id(), (key, previous) -> {
			if (previous != null && now.isBefore(previous.plus(TEST_INTERVAL))) {
				return previous;
			}
			allowed.set(true);
			return now;
		});
		if (!allowed.get()) {
			throw new TooManyTestsException();
		}
		this.senders.send(channel, Alert.test(now), "test-" + Ids.newId());
	}

	private Channel find(String projectId, String id) {
		return this.channels.findById(id)
			.filter((channel) -> channel.projectId().equals(projectId))
			.orElseThrow(() -> new ChannelNotFoundException(id));
	}

	private void requireEmailAvailable(ChannelType type) {
		if (type == ChannelType.EMAIL && !this.email.configured()) {
			throw new IllegalArgumentException(
					"email channels need SMTP settings on the server (CRONBELL_SMTP_HOST and CRONBELL_SMTP_FROM)");
		}
	}

	private static String name(String name, ChannelType type) {
		String result = (name != null && !name.isBlank()) ? name.trim() : type.id();
		if (result.length() > 200) {
			throw new IllegalArgumentException("name must be at most 200 characters");
		}
		return result;
	}

	private Instant now() {
		return this.clock.instant().truncatedTo(ChronoUnit.MILLIS);
	}

}
