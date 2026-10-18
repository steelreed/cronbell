import { describe, expect, it } from "vitest";
import { dayTitle, problemsFirst, stripSummary } from "./strip";
import type { CheckDays, DayState, StatusDay } from "./types";

const day = (date: string, state: DayState, extra: Partial<StatusDay> = {}): StatusDay => ({
  date,
  state,
  runs: 0,
  failures: 0,
  missed: 0,
  downMs: 0,
  ...extra,
});

const check = (name: string, ...states: DayState[]): CheckDays => ({
  slug: name,
  name,
  days: states.map((state, i) => day(`2026-01-0${i + 1}`, state)),
});

describe("problemsFirst", () => {
  it("puts checks DOWN today first, then trouble today, then the most bad days", () => {
    const sorted = problemsFirst([
      check("calm", "OK", "OK"),
      check("rough week", "DOWN", "OK"),
      check("down now", "OK", "DOWN"),
      check("flaky today", "OK", "ISSUES"),
      check("another calm", "NONE", "OK"),
    ]);
    expect(sorted.map((c) => c.name)).toEqual([
      "down now",
      "flaky today",
      "rough week",
      "another calm",
      "calm",
    ]);
  });
});

describe("dayTitle", () => {
  it("says what happened that day", () => {
    expect(
      dayTitle(day("2026-10-13", "DOWN", { runs: 24, failures: 1, missed: 1, downMs: 20_760_000 })),
    ).toBe("Tue 13 Oct · 24 runs, 1 failed · missed 1 run · down 5h 46m");
    expect(dayTitle(day("2026-10-14", "NONE"))).toBe("Wed 14 Oct · no runs");
  });

  it("counts the days of each kind for screen readers", () => {
    expect(stripSummary(check("x", "OK", "OK", "ISSUES", "DOWN", "NONE").days)).toBe(
      "2 good days, 1 with trouble, 1 down",
    );
  });
});
