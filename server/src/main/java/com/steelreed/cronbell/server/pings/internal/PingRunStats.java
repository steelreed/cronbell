package com.steelreed.cronbell.server.pings.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.steelreed.cronbell.server.checks.PingKind;
import com.steelreed.cronbell.server.checks.RunStats;
import com.steelreed.cronbell.server.pings.Ping;

import org.springframework.stereotype.Component;

/** {@link RunStats} from the stored pings. */
@Component
class PingRunStats implements RunStats {

	/**
	 * The average reads at most this many pings, so a job that runs every minute costs
	 * the same as one that runs every hour: about the last 1,000 runs with their starts.
	 */
	static final int MAX_PINGS = 2000;

	private final PingRepository pings;

	PingRunStats(PingRepository pings) {
		this.pings = pings;
	}

	@Override
	public Optional<Duration> averageDuration(String checkId, Instant since) {
		List<Ping> recent = this.pings.findRunPingsSince(checkId, since, MAX_PINGS);
		Map<String, Ping> starts = RunPairing.startsByFinish(recent);
		if (starts.isEmpty()) {
			return Optional.empty();
		}
		long total = 0;
		for (Ping finish : recent) {
			Ping start = starts.get(finish.id());
			if (start != null) {
				total += Duration.between(start.receivedAt(), finish.receivedAt()).toMillis();
			}
		}
		return Optional.of(Duration.ofMillis(total / starts.size()));
	}

	@Override
	public List<Count> countPerBucket(Collection<String> checkIds, List<Instant> boundaries) {
		Map<String, Count> counts = new LinkedHashMap<>();
		for (PingRepository.BucketCount count : this.pings.countFinishedByBucket(checkIds, boundaries)) {
			long failures = (count.kind() == PingKind.FAIL) ? count.count() : 0;
			counts.merge(count.checkId() + "/" + count.bucket(),
					new Count(count.checkId(), count.bucket(), count.count(), failures),
					(a, b) -> new Count(a.checkId(), a.bucket(), a.runs() + b.runs(), a.failures() + b.failures()));
		}
		return List.copyOf(counts.values());
	}

}
