package com.steelreed.cronbell.server.checks;

import java.util.Set;

/**
 * The alert channels a check can pick (DESIGN.md §8, Routing). The alerts module owns
 * channels and implements this, so the checks module can validate a selection without
 * depending on alerts.
 */
public interface AlertChannels {

	/** Ids of the alert channels of the project. */
	Set<String> idsOf(String projectId);

}
