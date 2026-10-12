import { describe, expect, it } from "vitest";
import { NOW } from "@/test/fixtures";
import {
  formatClock,
  formatDuration,
  formatRelative,
  formatSchedule,
  formatSeconds,
} from "./format";

describe("format", () => {
  it("writes relative times", () => {
    expect(formatRelative("2026-01-05T09:57:00Z", NOW)).toBe("3 min. ago");
    expect(formatRelative("2026-01-05T12:00:00Z", NOW)).toBe("in 2 hr.");
    expect(formatRelative(null, NOW)).toBe("never");
  });

  it("writes local clock times, with the day when it is not today", () => {
    const today = new Date(2026, 0, 5, 2, 0).toISOString();
    const yesterday = new Date(2026, 0, 4, 23, 30).toISOString();
    const now = new Date(2026, 0, 5, 9, 0).getTime();
    expect(formatClock(today, now)).toBe("02:00");
    expect(formatClock(yesterday, now)).toBe("4 Jan 23:30");
  });

  it("writes durations", () => {
    expect(formatSeconds(3600)).toBe("1h");
    expect(formatSeconds(5400)).toBe("1h 30m");
    expect(formatDuration(850)).toBe("850 ms");
    expect(formatDuration(12_400)).toBe("12.4 s");
    expect(formatDuration(185_000)).toBe("3m 5s");
  });

  it("writes schedules", () => {
    expect(formatSchedule({ scheduleKind: "PERIOD", cron: null, periodSeconds: 300 })).toBe(
      "every 5m",
    );
  });
});
