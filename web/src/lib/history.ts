import { formatClock } from "./format";
import { t } from "./strings";
import type { CheckAlert, StatusEvent } from "./types";

/** A stretch of a check's history worth a row: DOWN or paused, from start to end. */
export interface Episode {
  start: StatusEvent;
  /** What ended it (a recovery, a pause, a resume); null while it lasts. */
  end: StatusEvent | null;
  /** The alerts sent when it started, by channel name. */
  alerts: CheckAlert[];
}

/**
 * A check's status events (newest first, as the API gives them) as its DOWN and paused
 * episodes, newest first. Going UP only ends an episode, so it is no row of its own.
 */
export function episodes(events: StatusEvent[], alerts: CheckAlert[] = []): Episode[] {
  const result: Episode[] = [];
  let later: StatusEvent | null = null;
  for (const event of events) {
    if (event.kind === "DOWN" || event.kind === "PAUSED") {
      result.push({
        start: event,
        end: later,
        alerts: alerts
          .filter((alert) => alert.eventId === event.id)
          .toSorted((a, b) => a.channelName.localeCompare(b.channelName)),
      });
    }
    later = event;
  }
  return result;
}

/** Why a check went DOWN, in words: "missed the 02:00 run", "run failed". */
export function downReason(event: StatusEvent, now: number): string | null {
  switch (event.reason) {
    case "MISSED":
      return event.runAt
        ? t.check.history.reasons.MISSED(formatClock(event.runAt, now))
        : t.status.reasons.MISSED;
    case "FAILED":
      return t.check.history.reasons.FAILED;
    case "RUNNING_TOO_LONG":
      return t.check.history.reasons.RUNNING_TOO_LONG;
    default:
      return null;
  }
}

/** How long an episode lasted, or has lasted until `now`. */
export function episodeMs(episode: Episode, now: number): number {
  const end = episode.end ? Date.parse(episode.end.at) : now;
  return Math.max(0, end - Date.parse(episode.start.at));
}

/** The due times of the runs a check went DOWN for having missed. */
export function missedRuns(events: StatusEvent[]): string[] {
  return events.flatMap((event) =>
    event.kind === "DOWN" && event.reason === "MISSED" && event.runAt ? [event.runAt] : [],
  );
}
