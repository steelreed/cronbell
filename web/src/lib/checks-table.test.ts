import { describe, expect, it } from "vitest";
import { aCheck, NOW } from "@/test/fixtures";
import { filterChecks, matchesQuery, pageWindow, paginate, sortChecks } from "./checks-table";

const up = aCheck({ slug: "b-report", name: "Billing report" });
const down = aCheck({
  slug: "nightly-backup",
  name: "Nightly backup",
  status: "DOWN",
  downReason: "FAILED",
});
const late = aCheck({
  slug: "sync",
  name: "Sync",
  nextDueAt: "2026-01-05T09:50:00Z",
  lastPingAt: "2026-01-05T08:00:00Z",
});
const paused = aCheck({ slug: "a-paused", name: "Archive", status: "PAUSED", lastPingAt: null });
const all = [up, down, late, paused];

describe("filterChecks", () => {
  it("filters by status, with late as its own status", () => {
    expect(filterChecks(all, "", "down", NOW)).toEqual([down]);
    expect(filterChecks(all, "", "late", NOW)).toEqual([late]);
    expect(filterChecks(all, "", "up", NOW)).toEqual([up]);
    expect(filterChecks(all, "", "all", NOW)).toHaveLength(4);
  });

  it("searches names and slugs, ignoring case, accents and separators", () => {
    expect(matchesQuery(down, "NIGHTLY")).toBe(true);
    expect(matchesQuery(down, "nightly backup")).toBe(true);
    expect(matchesQuery(up, "b report")).toBe(true);
    expect(matchesQuery({ name: "Sao lưu", slug: "x" }, "luu")).toBe(true);
    expect(filterChecks(all, "zzz", "all", NOW)).toEqual([]);
  });
});

describe("sortChecks", () => {
  it("puts problems first by default, then names", () => {
    expect(sortChecks(all, "status", "asc", NOW).map((check) => check.slug)).toEqual([
      "nightly-backup",
      "sync",
      "b-report",
      "a-paused",
    ]);
  });

  it("sorts by last ping newest first and keeps checks never pinged last both ways", () => {
    expect(sortChecks(all, "lastPing", "desc", NOW).map((check) => check.slug)).toEqual([
      "sync",
      "b-report",
      "nightly-backup",
      "a-paused",
    ]);
    expect(sortChecks(all, "lastPing", "asc", NOW).at(-1)?.slug).toBe("a-paused");
  });

  it("sorts by name", () => {
    expect(sortChecks(all, "name", "asc", NOW).map((check) => check.name)).toEqual([
      "Archive",
      "Billing report",
      "Nightly backup",
      "Sync",
    ]);
  });
});

describe("pagination", () => {
  it("clamps the page and reports the range", () => {
    const items = Array.from({ length: 30 }, (_, i) => i);
    expect(paginate(items, 2, 25)).toMatchObject({
      page: 2,
      pages: 2,
      from: 26,
      to: 30,
      total: 30,
    });
    expect(paginate(items, 9, 25).page).toBe(2);
    expect(paginate([], 1, 25)).toMatchObject({ pages: 1, from: 0, to: 0, total: 0 });
  });

  it("always shows seven slots once there are more than seven pages", () => {
    expect(pageWindow(1, 5)).toEqual([1, 2, 3, 4, 5]);
    expect(pageWindow(1, 20)).toEqual([1, 2, 3, 4, 5, "gap", 20]);
    expect(pageWindow(10, 20)).toEqual([1, "gap", 9, 10, 11, "gap", 20]);
    expect(pageWindow(19, 20)).toEqual([1, "gap", 16, 17, 18, 19, 20]);
  });
});
