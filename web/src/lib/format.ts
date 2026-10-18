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

/** Run duration from milliseconds: "850 ms", "12.4 s", "3m 5s". */
export function formatDuration(ms: number | null): string {
  if (ms === null) return "";
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  return formatSeconds(Math.round(ms / 1000));
}

/**
 * A client address as people write it. Java gives IPv6 in full ("0:0:0:0:0:0:0:1"): shown
 * compressed ("::1", RFC 5952), and an IPv4-mapped address ("::ffff:10.0.0.7") as IPv4.
 */
export function formatAddress(address: string): string {
  const [ip, zone] = address.split("%");
  const groups = ip.includes(":") ? ipv6Groups(ip) : null;
  if (!groups) return address;
  if (groups.slice(0, 5).every((group) => group === 0) && groups[5] === 0xffff) {
    return [groups[6] >> 8, groups[6] & 0xff, groups[7] >> 8, groups[7] & 0xff].join(".");
  }
  return compressIpv6(groups) + (zone ? `%${zone}` : "");
}

/** The eight 16-bit groups of an IPv6 address, or null when it is not one. */
function ipv6Groups(ip: string): number[] | null {
  let text = ip;
  const v4 = /(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(text);
  if (v4) {
    const [a, b, c, d] = v4.slice(1).map(Number);
    text = `${text.slice(0, v4.index)}${((a << 8) | b).toString(16)}:${((c << 8) | d).toString(16)}`;
  }
  const halves = text.split("::");
  if (halves.length > 2) return null;
  const split = (part: string) => (part ? part.split(":") : []);
  const head = split(halves[0]);
  const tail = halves.length === 2 ? split(halves[1]) : [];
  const missing = 8 - head.length - tail.length;
  if (halves.length === 2 ? missing < 1 : missing !== 0) return null;
  const groups = [...head, ...Array<string>(halves.length === 2 ? missing : 0).fill("0"), ...tail];
  if (!groups.every((group) => /^[0-9a-f]{1,4}$/i.test(group))) return null;
  return groups.map((group) => Number.parseInt(group, 16));
}

/** The longest run of two or more zero groups (the first of equal runs) becomes "::". */
function compressIpv6(groups: number[]): string {
  let start = -1;
  let length = 1;
  for (let i = 0; i < groups.length; ) {
    let end = i;
    while (end < groups.length && groups[end] === 0) end++;
    if (end - i > length) [start, length] = [i, end - i];
    i = Math.max(end, i + 1);
  }
  const hex = groups.map((group) => group.toString(16));
  if (start < 0) return hex.join(":");
  return `${hex.slice(0, start).join(":")}::${hex.slice(start + length).join(":")}`;
}

export function formatSchedule(check: Pick<Check, "scheduleKind" | "cron" | "periodSeconds">) {
  return check.scheduleKind === "CRON"
    ? (check.cron ?? "")
    : `every ${formatSeconds(check.periodSeconds ?? 0)}`;
}
