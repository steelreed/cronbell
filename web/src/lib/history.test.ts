import { describe, expect, it } from "vitest";
import { anAlert, anEvent } from "@/test/fixtures";
import { episodeMs, episodes, missedRuns } from "./history";

describe("episodes", () => {
  const events = [
    anEvent("6", "PAUSED", "2026-01-05T09:00:00Z"),
    anEvent("5", "UP", "2026-01-04T08:01:00Z"),
    anEvent("4", "DOWN", "2026-01-04T02:15:00Z", {
      reason: "MISSED",
      runAt: "2026-01-04T02:00:00Z",
    }),
    anEvent("3", "RESUMED", "2026-01-03T12:00:00Z"),
    anEvent("2", "PAUSED", "2026-01-03T10:00:00Z"),
    anEvent("1", "UP", "2026-01-01T00:00:00Z"),
  ];

  it("makes a row of each DOWN and pause, ended by the next change", () => {
    const rows = episodes(events);
    expect(rows.map((row) => [row.start.id, row.end?.id ?? null])).toEqual([
      ["6", null],
      ["4", "5"],
      ["2", "3"],
    ]);
    expect(episodeMs(rows[1], 0)).toBe((5 * 60 + 46) * 60_000);
    expect(episodeMs(rows[0], Date.parse("2026-01-05T10:00:00Z"))).toBe(3_600_000);
  });

  it("joins the alerts each DOWN raised, by channel name", () => {
    const rows = episodes(events, [
      anAlert("a2", "4", "Telegram", "DELIVERED"),
      anAlert("a1", "4", "Email", "FAILED"),
      anAlert("a0", "5", "Telegram", "DELIVERED"),
    ]);
    expect(rows[1].alerts.map((a) => a.channelName)).toEqual(["Email", "Telegram"]);
    expect(rows[0].alerts).toEqual([]);
  });

  it("lists the runs a check went DOWN for having missed", () => {
    expect(missedRuns(events)).toEqual(["2026-01-04T02:00:00Z"]);
  });
});
