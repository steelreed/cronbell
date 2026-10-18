import { formatDuration } from "./format";
import { t } from "./strings";
import type { CheckDays, DayState, StatusDay } from "./types";

const bad = (day: StatusDay) => day.state === "DOWN" || day.state === "ISSUES";

/** DOWN today first, then trouble today, then the most days with trouble, then by name. */
export function problemsFirst(checks: CheckDays[]): CheckDays[] {
  const today = (check: CheckDays) => {
    const state = check.days.at(-1)?.state;
    return state === "DOWN" ? 0 : state === "ISSUES" ? 1 : 2;
  };
  const badDays = (check: CheckDays) => check.days.filter(bad).length;
  return checks.toSorted(
    (a, b) => today(a) - today(b) || badDays(b) - badDays(a) || a.name.localeCompare(b.name),
  );
}

/** "Tue 13 Oct": a day of the strip, which the server cut in the browser's zone. */
export function formatDay(date: string): string {
  const [year, month, day] = date.split("-").map(Number);
  return new Date(year, month - 1, day).toLocaleDateString("en-GB", {
    weekday: "short",
    day: "numeric",
    month: "short",
  });
}

/** The tooltip of a day: "Tue 13 Oct · 24 runs, 1 failed · missed 1 run · down 5h 46m". */
export function dayTitle(day: StatusDay): string {
  const s = t.overview.strip;
  const parts = [formatDay(day.date)];
  parts.push(day.runs === 0 ? s.noRuns : s.runs(day.runs, day.failures));
  if (day.missed > 0) parts.push(s.missed(day.missed));
  if (day.downMs > 0) parts.push(s.down(formatDuration(day.downMs)));
  return parts.join(" · ");
}

/** A check's strip for screen readers: how many days of each kind. */
export function stripSummary(days: StatusDay[]): string {
  const count = (state: DayState) => days.filter((day) => day.state === state).length;
  return t.overview.strip.summary(count("OK"), count("ISSUES"), count("DOWN"));
}
