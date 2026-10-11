import { describe, expect, it } from "vitest";
import { aCheck, NOW } from "@/test/fixtures";
import { statusView } from "./status";

describe("statusView", () => {
  it("shows UP in the OK tone without a reason", () => {
    expect(statusView(aCheck(), NOW)).toEqual({ label: "up", reason: null, tone: "ok" });
  });

  it("names the reason of a DOWN check", () => {
    expect(statusView(aCheck({ status: "DOWN", downReason: "MISSED" }), NOW)).toEqual({
      label: "down",
      reason: "missed run",
      tone: "problem",
    });
    expect(statusView(aCheck({ status: "DOWN", downReason: "RUNNING_TOO_LONG" }), NOW).reason).toBe(
      "running too long",
    );
  });

  it("flags a NEW check that has waited a day for its first ping", () => {
    const fresh = aCheck({ status: "NEW", createdAt: "2026-01-05T09:00:00Z" });
    const stale = aCheck({ status: "NEW", createdAt: "2026-01-04T09:00:00Z" });
    expect(statusView(fresh, NOW)).toMatchObject({ tone: "muted", reason: null });
    expect(statusView(stale, NOW)).toMatchObject({ tone: "warn", reason: "no ping in 24 h" });
  });

  it("shows PAUSED as a warning", () => {
    expect(statusView(aCheck({ status: "PAUSED" }), NOW).tone).toBe("warn");
  });
});
