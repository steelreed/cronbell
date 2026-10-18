import { describe, expect, it } from "vitest";
import { aCheck } from "@/test/fixtures";
import { axisTop, countTicks, groupRuns, runsFromPings, runTicks } from "./runs";
import type { Ping } from "./types";

const run = (at: string, ok: boolean, exitCode: number | null = ok ? 0 : 1) => ({
  at,
  ok,
  exitCode,
});

describe("runTicks", () => {
  it("pads missing history on the left and keeps the order, oldest first", () => {
    const ticks = runTicks(
      [run("2026-01-03T20:00:00Z", true), run("2026-01-04T20:00:00Z", false)],
      aCheck(),
      4,
    );
    expect(ticks.map((tick) => tick.kind)).toEqual(["empty", "empty", "ok", "fail"]);
    expect(ticks[3]).toMatchObject({ at: "2026-01-04T20:00:00Z", exitCode: 1 });
  });

  it("keeps only the latest runs when there are more than fit", () => {
    const runs = Array.from({ length: 30 }, (_, i) =>
      run(new Date(Date.UTC(2026, 0, 1, i)).toISOString(), i !== 29),
    );
    const ticks = runTicks(runs, aCheck(), 20);
    expect(ticks).toHaveLength(20);
    expect(ticks.at(-1)?.kind).toBe("fail");
    expect(countTicks(ticks)).toEqual({ ok: 19, fail: 1, missed: 0, empty: 0 });
  });

  it("adds the missed run a DOWN check is waiting for, after the last real run", () => {
    const check = aCheck({
      status: "DOWN",
      downReason: "MISSED",
      nextDueAt: "2026-01-05T20:00:00Z",
    });
    const ticks = runTicks([run("2026-01-04T20:00:00Z", true)], check, 3);
    expect(ticks.map((tick) => tick.kind)).toEqual(["empty", "ok", "missed"]);
    expect(ticks[2].at).toBe("2026-01-05T20:00:00Z");
  });

  it("places the runs missed in the past among the real ones, once each", () => {
    const check = aCheck({
      status: "DOWN",
      downReason: "MISSED",
      nextDueAt: "2026-01-05T20:00:00Z",
    });
    const ticks = runTicks(
      [run("2026-01-02T20:00:00Z", true), run("2026-01-04T20:00:00Z", true)],
      check,
      5,
      ["2026-01-05T20:00:00Z", "2026-01-03T20:00:00Z"],
    );
    expect(ticks.map((tick) => tick.kind)).toEqual(["empty", "ok", "missed", "ok", "missed"]);
    expect(ticks[2].at).toBe("2026-01-03T20:00:00Z");
  });

  it("does not invent missed runs for checks that are up or failed", () => {
    const failed = aCheck({ status: "DOWN", downReason: "FAILED" });
    expect(countTicks(runTicks([], failed, 5)).missed).toBe(0);
    expect(countTicks(runTicks([], aCheck(), 5))).toEqual({ ok: 0, fail: 0, missed: 0, empty: 5 });
  });
});

const ping = (
  id: string,
  kind: Ping["kind"],
  receivedAt: string,
  durationMs: number | null = null,
  extra: Partial<Ping> = {},
): Ping => ({
  id,
  kind,
  exitCode: kind === "FAIL" ? 2 : null,
  rid: null,
  body: null,
  receivedAt,
  remoteAddr: null,
  userAgent: null,
  durationMs,
  ...extra,
});

describe("runsFromPings", () => {
  it("keeps success and fail pings, oldest first, with their durations", () => {
    const runs = runsFromPings([
      ping("4", "FAIL", "2026-01-05T03:00:09Z", 9000),
      ping("3", "START", "2026-01-05T03:00:00Z"),
      ping("2", "LOG", "2026-01-04T12:00:00Z"),
      ping("1", "SUCCESS", "2026-01-04T03:00:05Z"),
    ]);
    expect(runs).toEqual([
      { at: "2026-01-04T03:00:05Z", ok: true, exitCode: null, durationMs: null },
      { at: "2026-01-05T03:00:09Z", ok: false, exitCode: 2, durationMs: 9000 },
    ]);
  });
});

describe("groupRuns", () => {
  it("pairs a finish with the start just before it, and keeps the output", () => {
    const runs = groupRuns([
      ping("3", "FAIL", "2026-01-05T03:01:10Z", null, { exitCode: 3, body: "disk full" }),
      ping("2", "LOG", "2026-01-05T03:00:30Z", null, { body: "halfway" }),
      ping("1", "START", "2026-01-05T03:00:00Z"),
    ]);
    expect(runs).toHaveLength(1);
    expect(runs[0]).toMatchObject({
      id: "1",
      startedAt: "2026-01-05T03:00:00Z",
      durationMs: 70_000,
      result: "fail",
      exitCode: 3,
      output: "disk full",
    });
    expect(runs[0].pings.map((p) => p.id)).toEqual(["1", "2", "3"]);
  });

  it("pairs overlapping runs by rid", () => {
    const runs = groupRuns([
      ping("4", "SUCCESS", "2026-01-05T03:00:50Z", null, { rid: "a" }),
      ping("3", "SUCCESS", "2026-01-05T03:00:40Z", null, { rid: "b" }),
      ping("2", "START", "2026-01-05T03:00:20Z", null, { rid: "b" }),
      ping("1", "START", "2026-01-05T03:00:00Z", null, { rid: "a" }),
    ]);
    expect(runs.map((r) => [r.id, r.durationMs])).toEqual([
      ["2", 20_000],
      ["1", 50_000],
    ]);
  });

  it("keeps pings without a partner as runs of their own", () => {
    const runs = groupRuns([
      ping("5", "START", "2026-01-05T05:00:00Z"),
      ping("4", "SUCCESS", "2026-01-05T04:00:00Z", null, { body: "done" }),
      ping("3", "LOG", "2026-01-05T03:30:00Z", null, { body: "note" }),
      ping("2", "START", "2026-01-05T03:00:00Z"),
      ping("1", "START", "2026-01-05T02:00:00Z"),
    ]);
    expect(runs.map((r) => [r.id, r.result, r.durationMs])).toEqual([
      ["5", "running", null],
      ["2", "ok", 3_600_000],
      ["1", "unfinished", null],
    ]);
    expect(runs[1].output).toBe("done");
    const alone = groupRuns([
      ping("2", "LOG", "2026-01-05T03:30:00Z", null, { body: "note" }),
      ping("1", "SUCCESS", "2026-01-05T03:00:00Z"),
    ]);
    expect(alone.map((r) => r.result)).toEqual(["log", "ok"]);
  });
});

describe("axisTop", () => {
  it("rounds the top of the duration axis up to two round steps", () => {
    expect(axisTop(77_000)).toBe(90_000);
    expect(axisTop(40_000)).toBe(60_000);
    expect(axisTop(2_100)).toBe(4_000);
    expect(axisTop(0)).toBe(200);
  });
});
