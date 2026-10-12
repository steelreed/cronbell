import { HEALTH_ORDER, HEALTHS, type Health, health } from "./status";
import type { Check } from "./types";

export type StatusFilter = "all" | Health;
export const STATUS_FILTERS: StatusFilter[] = ["all", ...HEALTHS];

export type SortKey = "status" | "name" | "lastPing" | "nextRun";
export const SORT_KEYS: SortKey[] = ["status", "name", "lastPing", "nextRun"];
export type SortDir = "asc" | "desc";

/** Text columns start A to Z; times start with the most useful end (newest ping, soonest run). */
export const DEFAULT_DIR: Record<SortKey, SortDir> = {
  status: "asc",
  name: "asc",
  lastPing: "desc",
  nextRun: "asc",
};

export const PAGE_SIZE = 25;

/** Lower case, no diacritics, '-', '_' and '.' as spaces. */
export function fold(text: string): string {
  return text
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[-_.]+/g, " ")
    .trim();
}

export function matchesQuery(check: Pick<Check, "name" | "slug">, query: string): boolean {
  const words = fold(query).split(/\s+/).filter(Boolean);
  if (words.length === 0) return true;
  const haystack = `${fold(check.name)} ${fold(check.slug)}`;
  return words.every((word) => haystack.includes(word));
}

export function filterChecks(
  checks: Check[],
  query: string,
  status: StatusFilter,
  now: number,
): Check[] {
  return checks.filter(
    (check) => (status === "all" || health(check, now) === status) && matchesQuery(check, query),
  );
}

/** When the next run is expected: the due time, or past a late one, the schedule's next run. */
function nextRunTime(check: Check, now: number): number | null {
  if (check.status === "PAUSED" || !check.nextDueAt) return null;
  const due = Date.parse(check.nextDueAt);
  if (due >= now) return due;
  return check.nextRunAt ? Date.parse(check.nextRunAt) : due;
}

function compareNullable(a: number | null, b: number | null, dir: SortDir): number {
  if (a === null && b === null) return 0;
  if (a === null) return 1;
  if (b === null) return -1;
  return dir === "asc" ? a - b : b - a;
}

/** Sorted copy. Empty values go last in both directions; ties break on the slug. */
export function sortChecks(checks: Check[], key: SortKey, dir: SortDir, now: number): Check[] {
  const sign = dir === "asc" ? 1 : -1;
  const byName = (a: Check, b: Check) =>
    a.name.localeCompare(b.name, "en", { sensitivity: "base" }) || a.slug.localeCompare(b.slug);
  return [...checks].sort((a, b) => {
    switch (key) {
      case "status":
        return sign * (HEALTH_ORDER[health(a, now)] - HEALTH_ORDER[health(b, now)]) || byName(a, b);
      case "name":
        return sign * byName(a, b);
      case "lastPing":
        return (
          compareNullable(
            a.lastPingAt ? Date.parse(a.lastPingAt) : null,
            b.lastPingAt ? Date.parse(b.lastPingAt) : null,
            dir,
          ) || byName(a, b)
        );
      default:
        return compareNullable(nextRunTime(a, now), nextRunTime(b, now), dir) || byName(a, b);
    }
  });
}

export interface Page<T> {
  items: T[];
  page: number;
  pages: number;
  from: number;
  to: number;
  total: number;
}

export function paginate<T>(items: T[], page: number, size: number = PAGE_SIZE): Page<T> {
  const pages = Math.max(1, Math.ceil(items.length / size));
  const current = Math.min(Math.max(1, page), pages);
  const start = (current - 1) * size;
  const slice = items.slice(start, start + size);
  return {
    items: slice,
    page: current,
    pages,
    from: slice.length ? start + 1 : 0,
    to: start + slice.length,
    total: items.length,
  };
}

/** Seven slots, so the pager never changes width: 1 2 3 4 5 … 9, 1 … 4 5 6 … 9, 1 … 5 6 7 8 9. */
export function pageWindow(page: number, pages: number): (number | "gap")[] {
  if (pages <= 7) return Array.from({ length: pages }, (_, i) => i + 1);
  if (page <= 4) return [1, 2, 3, 4, 5, "gap", pages];
  if (page >= pages - 3) return [1, "gap", pages - 4, pages - 3, pages - 2, pages - 1, pages];
  return [1, "gap", page - 1, page, page + 1, "gap", pages];
}
