package com.steelreed.cronbell.server.checks;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Figures about the runs of checks, which only their pings tell. The pings module owns
 * the pings and implements this; the checks module cannot read them itself, since pings
 * depends on checks.
 */
public interface RunStats {

	/**
	 * The average duration of the runs of a check that started and finished since
	 * {@code since}; empty if none was timed.
	 */
	Optional<Duration> averageDuration(String checkId, Instant since);

	/**
	 * Finished and failed runs per check and bucket, where bucket {@code i} is
	 * {@code boundaries[i] <= at < boundaries[i + 1]}. Buckets without runs are left out.
	 * @param boundaries at least two instants, ascending
	 */
	List<Count> countPerBucket(Collection<String> checkIds, List<Instant> boundaries);

	record Count(String checkId, int bucket, long runs, long failures) {
	}

}
