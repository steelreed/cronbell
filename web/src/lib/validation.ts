import { t } from "./strings";
import type { CheckDefinition } from "./types";

export const SLUG = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const CRON_MACROS = new Set([
  "@yearly",
  "@annually",
  "@monthly",
  "@weekly",
  "@daily",
  "@midnight",
  "@hourly",
]);

export type Unit = "s" | "m" | "h" | "d";
export const UNIT_SECONDS: Record<Unit, number> = { s: 1, m: 60, h: 3600, d: 86400 };

export interface CheckFormValues {
  slug: string;
  name: string;
  kind: "CRON" | "PERIOD";
  cron: string;
  period: string;
  periodUnit: Unit;
  timeZone: string;
  grace: string;
  graceUnit: Unit;
  maxRuntime: string;
  maxRuntimeUnit: Unit;
}

export type CheckFormErrors = Partial<Record<keyof CheckFormValues, string>>;

export function isCronLike(value: string): boolean {
  const trimmed = value.trim();
  if (CRON_MACROS.has(trimmed.toLowerCase())) return true;
  const fields = trimmed.split(/\s+/).filter(Boolean);
  return fields.length === 5 || fields.length === 6;
}

function toSeconds(value: string, unit: Unit): number | null {
  if (value.trim() === "") return null;
  const number = Number(value);
  return Number.isFinite(number) ? Math.round(number * UNIT_SECONDS[unit]) : Number.NaN;
}

/** Client-side checks that mirror the server (DESIGN.md §4, §6). The server has the final say. */
export function validateCheckForm(
  values: CheckFormValues,
  isZone: (zone: string) => boolean = isValidTimeZone,
): CheckFormErrors {
  const errors: CheckFormErrors = {};
  if (!SLUG.test(values.slug)) errors.slug = t.form.errors.slug;
  if (values.name.trim().length > 200) errors.name = t.form.errors.name;
  if (values.kind === "CRON") {
    if (!isCronLike(values.cron)) errors.cron = t.form.errors.cron;
  } else {
    const period = toSeconds(values.period, values.periodUnit);
    if (period === null || Number.isNaN(period) || period < 60)
      errors.period = t.form.errors.period;
  }
  if (!isZone(values.timeZone)) errors.timeZone = t.form.errors.timeZone;
  const grace = toSeconds(values.grace, values.graceUnit);
  if (grace === null || Number.isNaN(grace) || grace < 0) errors.grace = t.form.errors.grace;
  const maxRuntime = toSeconds(values.maxRuntime, values.maxRuntimeUnit);
  if (maxRuntime !== null && (Number.isNaN(maxRuntime) || maxRuntime <= 0)) {
    errors.maxRuntime = t.form.errors.maxRuntime;
  }
  return errors;
}

export function toDefinition(values: CheckFormValues): CheckDefinition {
  const definition: CheckDefinition = {
    timeZone: values.timeZone.trim(),
    graceSeconds: toSeconds(values.grace, values.graceUnit) ?? undefined,
  };
  if (values.name.trim()) definition.name = values.name.trim();
  if (values.kind === "CRON") definition.cron = values.cron.trim();
  else definition.periodSeconds = toSeconds(values.period, values.periodUnit) ?? undefined;
  const maxRuntime = toSeconds(values.maxRuntime, values.maxRuntimeUnit);
  if (maxRuntime !== null) definition.maxRuntimeSeconds = maxRuntime;
  return definition;
}

/** Largest unit that divides the value exactly, for editing. */
export function splitSeconds(seconds: number | null): { value: string; unit: Unit } {
  if (seconds === null) return { value: "", unit: "m" };
  for (const unit of ["d", "h", "m"] as const) {
    if (seconds > 0 && seconds % UNIT_SECONDS[unit] === 0) {
      return { value: String(seconds / UNIT_SECONDS[unit]), unit };
    }
  }
  return { value: String(seconds), unit: "s" };
}

/**
 * Any IANA zone the browser knows, including links such as Asia/Ho_Chi_Minh that its
 * own list may show under another name (Asia/Saigon).
 */
export function isValidTimeZone(zone: string): boolean {
  if (!zone.trim()) return false;
  try {
    new Intl.DateTimeFormat("en", { timeZone: zone.trim() });
    return true;
  } catch {
    return false;
  }
}

export function supportedTimeZones(): string[] {
  const zones =
    typeof Intl.supportedValuesOf === "function" ? Intl.supportedValuesOf("timeZone") : [];
  return zones.includes("UTC") ? zones : ["UTC", ...zones];
}

export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
}
