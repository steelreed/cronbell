package com.steelreed.cronbell.server.alerts.internal;

import java.util.Set;

import com.steelreed.cronbell.server.checks.AlertChannels;

import org.springframework.stereotype.Component;

/** Tells the checks module which channels a check of the project may pick. */
@Component
class ProjectAlertChannels implements AlertChannels {

	private final ChannelRepository channels;

	ProjectAlertChannels(ChannelRepository channels) {
		this.channels = channels;
	}

	@Override
	public Set<String> idsOf(String projectId) {
		return this.channels.findIdsByProject(projectId);
	}

}
