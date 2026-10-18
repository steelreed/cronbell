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
 * The ticks of a run bar, oldest first, exactly `size` long. A run is a success or fail ping.
 * A missed run leaves no ping: its ticks come from `missed`, the due times the check went DOWN
 * for (from its status history), and from the run a DOWN check is waiting for (its
 * nextDueAt). Missing history is padded with empty ticks on the left.
 */
export function runTicks(
  runs: RecentRun[] | Run[],
  check: Pick<Check, "status" | "downReason" | "nextDueAt">,
  size: number,
  missed: string[] = [],
): Tick[] {
  const ticks: Tick[] = runs.map((run) => ({
    kind: run.ok ? "ok" : "fail",
    at: run.at,
    exitCode: run.exitCode,
    durationMs: "durationMs" in run ? run.durationMs : null,
  }));
  const missedAt = new Set(missed);
  const last = runs.at(-1);
  if (
    check.status === "DOWN" &&
    check.downReason === "MISSED" &&
    check.nextDueAt &&
    (!last || Date.parse(last.at) < Date.parse(check.nextDueAt))
  ) {
    missedAt.add(check.nextDueAt);
  }
  for (const at of missedAt) {
    ticks.push({ kind: "missed", at, exitCode: null, durationMs: null });
  }
  ticks.sort((a, b) => Date.parse(a.at ?? "") - Date.parse(b.at ?? ""));
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

export type RunResult = "ok" | "fail" | "running" | "unfinished" | "log";

/** One run of a check as its pings tell it: a start with its finish, or a ping on its own. */
export interface RunGroup {
  /** The id of its first ping. */
  id: string;
  startedAt: string;
  durationMs: number | null;
  result: RunResult;
  exitCode: number | null;
  /** The finish's output, else the latest log's or the start's. */
  output: string | null;
  /** Its pings, oldest first. */
  pings: Ping[];
}

/**
 * A check's pings (newest first, as the API gives them) as runs, newest first. A finish pairs
 * with the open start of the same rid; without one, with the start just before it. Logs join
 * the open run. Anything else is a run of its own: a finish without a start, a log between
 * runs, a start that never finished ("running" while it is the latest run).
 */
export function groupRuns(pings: Ping[]): RunGroup[] {
  const groups: RunGroup[] = [];
  const open: RunGroup[] = [];
  let previous: Ping | null = null;
  for (const ping of pings.toReversed()) {
    if (ping.kind === "LOG") {
      const run = open.at(-1);
      if (run) {
        run.pings.push(ping);
        run.output = ping.body ?? run.output;
      } else {
        groups.push(single(ping, "log"));
      }
      continue;
    }
    if (ping.kind === "START") {
      const run = single(ping, "running");
      groups.push(run);
      open.push(run);
    } else {
      const byRid = ping.rid ? open.find((run) => run.pings[0].rid === ping.rid) : undefined;
      const adjacent =
        previous?.kind === "START" ? open.find((run) => run.pings[0] === previous) : undefined;
      const run = byRid ?? adjacent;
      const ok = ping.kind === "SUCCESS";
      if (run) {
        open.splice(open.indexOf(run), 1);
        run.pings.push(ping);
        run.durationMs = Date.parse(ping.receivedAt) - Date.parse(run.startedAt);
        run.result = ok ? "ok" : "fail";
        run.exitCode = ping.exitCode;
        run.output = ping.body ?? run.output;
      } else {
        groups.push({ ...single(ping, ok ? "ok" : "fail"), exitCode: ping.exitCode });
      }
    }
    previous = ping;
  }
  const latest = groups.at(-1);
  for (const run of open) {
    if (run !== latest) run.result = "unfinished";
  }
  return groups.reverse();
}

function single(ping: Ping, result: RunResult): RunGroup {
  return {
    id: ping.id,
    startedAt: ping.receivedAt,
    durationMs: null,
    result,
    exitCode: null,
    output: ping.body,
    pings: [ping],
  };
}

/** Steps of the duration axis, in ms: its top is two of them, so the labels stay round. */
const AXIS_STEPS = [
  100, 200, 250, 500, 1_000, 2_000, 3_000, 5_000, 10_000, 15_000, 20_000, 30_000, 45_000, 60_000,
  90_000, 120_000, 180_000, 300_000, 600_000, 900_000, 1_200_000, 1_800_000, 2_700_000, 3_600_000,
  5_400_000, 7_200_000, 10_800_000, 21_600_000, 43_200_000, 86_400_000,
];

/** The top of the duration axis: the smallest round value a little above `ms`. */
export function axisTop(ms: number): number {
  const step = AXIS_STEPS.find((value) => value * 2 >= ms * 1.05) ?? Math.ceil(ms / 2);
  return step * 2;
}

export function countTicks(ticks: Tick[]): Record<TickKind, number> {
  const counts: Record<TickKind, number> = { ok: 0, fail: 0, missed: 0, empty: 0 };
  for (const tick of ticks) counts[tick.kind]++;
  return counts;
}
