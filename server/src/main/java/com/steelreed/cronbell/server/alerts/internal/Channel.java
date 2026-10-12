package com.steelreed.cronbell.server.alerts.internal;

import java.time.Instant;
import java.util.Map;

/**
 * An alert channel of a project. {@code config} holds secrets: never log it, and return
 * it only through {@link ChannelConfigs#masked}.
 */
public record Channel(String id, String projectId, ChannelType type, String name, Map<String, String> config,
		Instant createdAt, Instant updatedAt) {

	public String get(String key) {
		return this.config.get(key);
	}

	/** Without the config, so a channel can be logged. */
	@Override
	public String toString() {
		return "Channel[id=" + this.id + ", type=" + this.type.id() + "]";
	}

}
