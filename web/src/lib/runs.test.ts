import { describe, expect, it } from "vitest";
import { aCheck } from "@/test/fixtures";
import { countTicks, runsFromPings, runTicks } from "./runs";
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

  it("does not invent missed runs for checks that are up or failed", () => {
    const failed = aCheck({ status: "DOWN", downReason: "FAILED" });
    expect(countTicks(runTicks([], failed, 5)).missed).toBe(0);
    expect(countTicks(runTicks([], aCheck(), 5))).toEqual({ ok: 0, fail: 0, missed: 0, empty: 5 });
  });
});

describe("runsFromPings", () => {
  it("keeps success and fail pings, oldest first, with their durations", () => {
    const ping = (
      id: string,
      kind: Ping["kind"],
      receivedAt: string,
      durationMs: number | null = null,
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
    });
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
