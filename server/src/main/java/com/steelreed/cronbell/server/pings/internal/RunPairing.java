package com.steelreed.cronbell.server.pings.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.steelreed.cronbell.server.checks.PingKind;
import com.steelreed.cronbell.server.pings.Ping;

/**
 * Pairs each {@code success}/{@code fail} with its {@code start} (DESIGN.md §6): the one
 * with the same rid, else the latest start not yet closed.
 */
public final class RunPairing {

	private RunPairing() {
	}

	/**
	 * The start of each finish ping that has one in the list, by finish ping id.
	 * @param newestFirst pings of one check, newest first
	 */
	public static Map<String, Ping> startsByFinish(List<Ping> newestFirst) {
		List<Ping> open = new ArrayList<>();
		Map<String, Ping> starts = new HashMap<>();
		for (Ping ping : newestFirst.reversed()) {
			if (ping.kind() == PingKind.START) {
				open.add(ping);
			}
			else if (ping.kind() != PingKind.LOG && !open.isEmpty()) {
				Ping start = open.stream()
					.filter((candidate) -> ping.rid() != null && ping.rid().equals(candidate.rid()))
					.findFirst()
					.orElse(open.getLast());
				open.remove(start);
				starts.put(ping.id(), start);
			}
		}
		return starts;
	}

}
