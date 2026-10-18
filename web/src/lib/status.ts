import { formatClock } from "./format";
import { t } from "./strings";
import type { Check } from "./types";

export type Tone = "ok" | "problem" | "warn" | "muted";

/** A NEW check that has waited this long for its first ping is flagged (DESIGN.md §3). */
export const STALE_NEW_MS = 24 * 60 * 60 * 1000;

/**
 * What the dashboard shows for a check: the server status plus "late", an UP check whose
 * run is overdue but still within its grace time (not DOWN yet).
 */
export type Health = "down" | "late" | "up" | "new" | "paused";

export const HEALTHS: Health[] = ["down", "late", "up", "new", "paused"];

/** Order for lists: problems first. */
export const HEALTH_ORDER: Record<Health, number> = { down: 0, late: 1, new: 2, up: 3, paused: 4 };

type StatusFields = Pick<Check, "status" | "downReason" | "createdAt"> &
  Partial<Pick<Check, "nextDueAt">>;

export function health(check: StatusFields, now: number = Date.now()): Health {
  switch (check.status) {
    case "DOWN":
      return "down";
    case "PAUSED":
      return "paused";
    case "NEW":
      return "new";
    default:
      return check.nextDueAt && Date.parse(check.nextDueAt) < now ? "late" : "up";
  }
}

export interface StatusView {
  label: string;
  reason: string | null;
  tone: Tone;
}

export function statusView(check: StatusFields, now: number = Date.now()): StatusView {
  switch (health(check, now)) {
    case "up":
      return { label: t.status.UP, reason: null, tone: "ok" };
    case "late":
      return { label: t.status.LATE, reason: t.status.lateReason, tone: "warn" };
    case "down":
      return {
        label: t.status.DOWN,
        reason: check.downReason ? t.status.reasons[check.downReason] : null,
        tone: "problem",
      };
    case "paused":
      return { label: t.status.PAUSED, reason: null, tone: "muted" };
    default: {
      const stale = now - new Date(check.createdAt).getTime() > STALE_NEW_MS;
      return {
        label: t.status.NEW,
        reason: stale ? t.status.stale : null,
        tone: stale ? "warn" : "muted",
      };
    }
  }
}

export type DueView =
  | { kind: "none" }
  | { kind: "upcoming"; at: string }
  | { kind: "overdue"; missed: boolean; at: string; next: string | null };

/**
 * What to show as "next run". Once the due time has passed it is not "due 3 min ago": a DOWN
 * MISSED check names the run it missed, anything else is late; both add the next run the
 * schedule expects, when the server knows one (nextRunAt).
 */
export function dueView(
  check: Pick<Check, "status" | "downReason" | "nextDueAt" | "nextRunAt">,
  now: number = Date.now(),
): DueView {
  if (check.status === "PAUSED" || !check.nextDueAt) return { kind: "none" };
  if (Date.parse(check.nextDueAt) >= now) return { kind: "upcoming", at: check.nextDueAt };
  return {
    kind: "overdue",
    missed: check.status === "DOWN" && check.downReason === "MISSED",
    at: check.nextDueAt,
    next: check.nextRunAt,
  };
}

/** A check that needs someone to look at it, with what went wrong and since when. */
export interface Attention {
  check: Check;
  health: "down" | "late" | "new";
  tone: Tone;
  /** What happened, in a few words: "Run failed, exit 3", "Missed 02:00 run". */
  reason: string;
  /** When it started, as far as the check's state tells. */
  since: string | null;
}

/**
 * Why a check needs attention, or null when it does not. The time comes from the check's
 * own state (status changes are not stored): the failing ping, the missed run, the start of
 * the run that is taking too long, the overdue run, or the creation of a check never pinged.
 */
export function attention(check: Check, now: number = Date.now()): Attention | null {
  const state = health(check, now);
  if (state === "down") {
    switch (check.downReason) {
      case "MISSED":
        return {
          check,
          health: state,
          tone: "problem",
          reason: check.nextDueAt
            ? t.attention.missed(formatClock(check.nextDueAt, now))
            : t.status.reasons.MISSED,
          since: check.nextDueAt,
        };
      case "RUNNING_TOO_LONG":
        return {
          check,
          health: state,
          tone: "problem",
          reason: t.attention.tooLong,
          since: check.lastStartAt,
        };
      default:
        return {
          check,
          health: state,
          tone: "problem",
          reason: t.attention.failed,
          since: check.lastPingAt,
        };
    }
  }
  if (state === "late" && check.nextDueAt) {
    return {
      check,
      health: state,
      tone: "warn",
      reason: t.attention.late(formatClock(check.nextDueAt, now)),
      since: check.nextDueAt,
    };
  }
  if (state === "new" && now - Date.parse(check.createdAt) > STALE_NEW_MS) {
    return {
      check,
      health: state,
      tone: "warn",
      reason: t.attention.noPing,
      since: check.createdAt,
    };
  }
  return null;
}

/** Checks that need attention, the worst first, then the longest-standing. */
export function needsAttention(checks: Check[], now: number = Date.now()): Attention[] {
  return checks
    .map((check) => attention(check, now))
    .filter((item): item is Attention => item !== null)
    .sort(
      (a, b) =>
        HEALTH_ORDER[a.health] - HEALTH_ORDER[b.health] ||
        Date.parse(a.since ?? "") - Date.parse(b.since ?? "") ||
        a.check.slug.localeCompare(b.check.slug),
    );
}

export function countByHealth(checks: Check[], now: number = Date.now()): Record<Health, number> {
  const counts: Record<Health, number> = { down: 0, late: 0, up: 0, new: 0, paused: 0 };
  for (const check of checks) counts[health(check, now)]++;
  return counts;
}
