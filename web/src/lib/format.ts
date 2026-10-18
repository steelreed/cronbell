import { t } from "./strings";
import type { Check } from "./types";

const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ["day", 86_400],
  ["hour", 3_600],
  ["minute", 60],
  ["second", 1],
];

const relative = new Intl.RelativeTimeFormat("en", { numeric: "auto", style: "short" });

/** "3 min ago", "in 2 hr", "now". */
export function formatRelative(iso: string | null, now: number = Date.now()): string {
  if (!iso) return t.common.never;
  const seconds = Math.round((new Date(iso).getTime() - now) / 1000);
  const abs = Math.abs(seconds);
  if (abs < 5) return "now";
  for (const [unit, size] of UNITS) {
    if (abs >= size || unit === "second") {
      return relative.format(Math.trunc(seconds / size), unit);
    }
  }
  return iso;
}

/** A local date and time to the minute: "12 Jan 2026, 17:00". */
export function formatDate(iso: string): string {
  return new Date(iso).toLocaleString("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** Exact local time with the offset, for tooltips. */
export function formatExact(iso: string | null): string {
  if (!iso) return t.common.never;
  return new Date(iso).toLocaleString("en-GB", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    timeZoneName: "short",
  });
}

/** A run time in a list: "Tue 13 Oct, 03:00", with the year only when it is not this year. */
export function formatRun(iso: string, now: number = Date.now()): string {
  const date = new Date(iso);
  const sameYear = date.getFullYear() === new Date(now).getFullYear();
  return date.toLocaleString("en-GB", {
    weekday: "short",
    day: "numeric",
    month: "short",
    year: sameYear ? undefined : "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** Local clock time, "02:00"; with the day when it is not today: "4 Jan 02:00". */
export function formatClock(iso: string, now: number = Date.now()): string {
  const date = new Date(iso);
  const time = date.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
  if (date.toDateString() === new Date(now).toDateString()) return time;
  return `${date.toLocaleDateString("en-GB", { day: "numeric", month: "short" })} ${time}`;
}

/** 90 -> "1m 30s", 3600 -> "1h", 86400 -> "1d". */
export function formatSeconds(total: number): string {
  if (total === 0) return "0s";
  const parts: string[] = [];
  let rest = total;
  for (const [label, size] of [
    ["d", 86_400],
    ["h", 3_600],
    ["m", 60],
    ["s", 1],
  ] as const) {
    if (rest >= size) {
      parts.push(`${Math.floor(rest / size)}${label}`);
      rest %= size;
    }
  }
  return parts.slice(0, 2).join(" ");
}

/**
 * A duration from milliseconds, in the style of {@link formatSeconds} so every duration on a
 * page reads alike: "0s", "850ms", "12.4s", "40s", "3m 5s", "5h 46m".
 */
export function formatDuration(ms: number | null): string {
  if (ms === null) return "";
  if (ms === 0) return "0s";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) return `${Number((ms / 1000).toFixed(1))}s`;
  return formatSeconds(Math.round(ms / 1000));
}

/** A share from 0 to 1 as a percentage, rounded down so only a full 1 reads "100%": "99.9%". */
export function formatPercent(share: number): string {
  return `${Math.floor(share * 1000) / 10}%`;
}

export function formatSchedule(check: Pick<Check, "scheduleKind" | "cron" | "periodSeconds">) {
  return check.scheduleKind === "CRON"
    ? (check.cron ?? "")
    : `every ${formatSeconds(check.periodSeconds ?? 0)}`;
}
