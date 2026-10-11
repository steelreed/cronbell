import { t } from "./strings";
import type { Check, CheckStatus } from "./types";

export type Tone = "ok" | "problem" | "warn" | "muted";

/** A NEW check that has waited this long for its first ping is flagged (DESIGN.md §3). */
export const STALE_NEW_MS = 24 * 60 * 60 * 1000;

export interface StatusView {
  label: string;
  reason: string | null;
  tone: Tone;
}

export function statusView(
  check: Pick<Check, "status" | "downReason" | "createdAt">,
  now: number = Date.now(),
): StatusView {
  switch (check.status) {
    case "UP":
      return { label: t.status.UP, reason: null, tone: "ok" };
    case "DOWN":
      return {
        label: t.status.DOWN,
        reason: check.downReason ? t.status.reasons[check.downReason] : null,
        tone: "problem",
      };
    case "PAUSED":
      return { label: t.status.PAUSED, reason: null, tone: "warn" };
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

/** Order for the list: problems first. */
export const STATUS_ORDER: Record<CheckStatus, number> = { DOWN: 0, NEW: 1, UP: 2, PAUSED: 3 };
