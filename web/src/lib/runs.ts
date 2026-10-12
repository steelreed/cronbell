import type { Check, Ping, RecentRun } from "./types";

export type TickKind = "ok" | "fail" | "missed" | "empty";

export interface Tick {
  kind: TickKind;
  at: string | null;
  exitCode: number | null;
  durationMs: number | null;
}

export interface Run extends RecentRun {
  durationMs: number | null;
}

/**
 * The ticks of a run bar, oldest first, exactly `size` long. A run is a success or fail ping;
 * a missed run leaves no ping, so the only missed tick is the one the check is DOWN for (its
 * nextDueAt), added at the end. Missing history is padded with empty ticks on the left.
 */
export function runTicks(
  runs: RecentRun[] | Run[],
  check: Pick<Check, "status" | "downReason" | "nextDueAt">,
  size: number,
): Tick[] {
  const ticks: Tick[] = runs.map((run) => ({
    kind: run.ok ? "ok" : "fail",
    at: run.at,
    exitCode: run.exitCode,
    durationMs: "durationMs" in run ? run.durationMs : null,
  }));
  const last = runs.at(-1);
  if (
    check.status === "DOWN" &&
    check.downReason === "MISSED" &&
    check.nextDueAt &&
    (!last || Date.parse(last.at) < Date.parse(check.nextDueAt))
  ) {
    ticks.push({ kind: "missed", at: check.nextDueAt, exitCode: null, durationMs: null });
  }
  const recent = ticks.slice(-size);
  const padding: Tick[] = Array.from({ length: size - recent.length }, () => ({
    kind: "empty",
    at: null,
    exitCode: null,
    durationMs: null,
  }));
  return [...padding, ...recent];
}

/** Finished runs from a check's pings (newest first, as the API gives them), oldest first. */
export function runsFromPings(pings: Ping[]): Run[] {
  return pings
    .filter((ping) => ping.kind === "SUCCESS" || ping.kind === "FAIL")
    .map((ping) => ({
      at: ping.receivedAt,
      ok: ping.kind === "SUCCESS",
      exitCode: ping.exitCode,
      durationMs: ping.durationMs,
    }))
    .reverse();
}

export function countTicks(ticks: Tick[]): Record<TickKind, number> {
  const counts: Record<TickKind, number> = { ok: 0, fail: 0, missed: 0, empty: 0 };
  for (const tick of ticks) counts[tick.kind]++;
  return counts;
}
