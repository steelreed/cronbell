package com.steelreed.cronbell.server.alerts.internal;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Where alerts go (DESIGN.md §8). In the API a type is its lower-case name. */
public enum ChannelType {

	EMAIL, TELEGRAM, SLACK, WEBHOOK;

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * The type with this API name.
	 * @throws IllegalArgumentException if there is none
	 */
	public static ChannelType of(String id) {
		if (id != null) {
			String wanted = id.trim().toLowerCase(Locale.ROOT);
			for (ChannelType type : values()) {
				if (type.id().equals(wanted)) {
					return type;
				}
			}
		}
		throw new IllegalArgumentException("type must be one of "
				+ Arrays.stream(values()).map(ChannelType::id).collect(Collectors.joining(", ")));
	}

}
