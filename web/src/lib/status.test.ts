import { describe, expect, it } from "vitest";
import { aCheck, NOW } from "@/test/fixtures";
import { dueView, statusView } from "./status";

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

describe("dueView", () => {
  it("shows an upcoming due time as it is", () => {
    expect(dueView(aCheck(), NOW)).toEqual({ kind: "upcoming", at: "2026-01-05T20:00:00Z" });
  });

  it("shows nothing for paused and never-pinged checks", () => {
    expect(dueView(aCheck({ status: "PAUSED" }), NOW)).toEqual({ kind: "none" });
    expect(dueView(aCheck({ status: "NEW", nextDueAt: null, nextRunAt: null }), NOW)).toEqual({
      kind: "none",
    });
  });

  it("names the missed run and the next one instead of a past due time", () => {
    const missed = aCheck({
      status: "DOWN",
      downReason: "MISSED",
      nextDueAt: "2026-01-05T09:00:00Z",
      nextRunAt: "2026-01-05T11:00:00Z",
    });
    expect(dueView(missed, NOW)).toEqual({
      kind: "overdue",
      missed: true,
      at: "2026-01-05T09:00:00Z",
      next: "2026-01-05T11:00:00Z",
    });
  });

  it("calls an overdue run late while the check is not down for a missed run", () => {
    const late = aCheck({ nextDueAt: "2026-01-05T09:58:00Z", nextRunAt: null });
    expect(dueView(late, NOW)).toMatchObject({ kind: "overdue", missed: false, next: null });
  });
});
