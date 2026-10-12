// Time zones for the zone picker, as on steelreed.com/tools/cron: common zones first, the
// current UTC offset next to each, search by id, city, alias or offset ("+7"). Pure Intl.

export const COMMON_ZONES: readonly string[] = [
  "UTC",
  "Asia/Ho_Chi_Minh",
  "Asia/Bangkok",
  "Asia/Singapore",
  "Asia/Tokyo",
  "Europe/London",
  "Europe/Berlin",
  "America/New_York",
  "America/Los_Angeles",
  "Australia/Sydney",
];

/** Old names some browsers still list, mapped to the current IANA name. */
const RENAMED: Readonly<Record<string, string>> = {
  "Asia/Saigon": "Asia/Ho_Chi_Minh",
  "Asia/Calcutta": "Asia/Kolkata",
  "Asia/Katmandu": "Asia/Kathmandu",
  "Asia/Rangoon": "Asia/Yangon",
  "Europe/Kiev": "Europe/Kyiv",
  "Atlantic/Faeroe": "Atlantic/Faroe",
  "America/Godthab": "America/Nuuk",
  "Pacific/Truk": "Pacific/Chuuk",
  "Pacific/Ponape": "Pacific/Pohnpei",
  "Pacific/Enderbury": "Pacific/Kanton",
};

const ALIASES: Readonly<Record<string, readonly string[]>> = {
  "Asia/Ho_Chi_Minh": ["Vietnam", "Saigon", "Sai Gon", "Hanoi", "Ha Noi", "HCM", "HCMC"],
  UTC: ["GMT", "Zulu"],
};

/** The current IANA name of a zone that browsers may list under an old one. */
export function canonicalZone(zone: string): string {
  const renamed = RENAMED[zone];
  return renamed && isValidZone(renamed) ? renamed : zone;
}

export function isValidZone(zone: string): boolean {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

let all: string[] | null = null;

/** Every zone the browser knows (current names, sorted), plus UTC and the common ones. */
export function allZones(): string[] {
  if (all) return all;
  let list: string[] = [];
  try {
    list = Intl.supportedValuesOf("timeZone");
  } catch {
    list = [...COMMON_ZONES];
  }
  const zones = new Set<string>(["UTC"]);
  for (const zone of list) {
    const renamed = RENAMED[zone];
    zones.add(renamed && isValidZone(renamed) ? renamed : zone);
  }
  for (const zone of COMMON_ZONES) if (isValidZone(zone)) zones.add(zone);
  all = [...zones].sort();
  return all;
}

const formatters = new Map<string, Intl.DateTimeFormat>();

/** UTC offset of a zone at an instant, in minutes. */
export function zoneOffset(zone: string, at: number = Date.now()): number {
  let format = formatters.get(zone);
  if (!format) {
    try {
      format = new Intl.DateTimeFormat("en-US", { timeZone: zone, timeZoneName: "longOffset" });
    } catch {
      return 0;
    }
    formatters.set(zone, format);
  }
  const name = format.formatToParts(at).find((part) => part.type === "timeZoneName")?.value ?? "";
  const match = /GMT([+-−])(\d{1,2}):?(\d{2})?/.exec(name);
  if (!match) return 0;
  return (match[1] === "+" ? 1 : -1) * (Number(match[2]) * 60 + Number(match[3] ?? 0));
}

/** "+07:00", "−03:30", "+00:00". */
export function offsetText(minutes: number): string {
  const abs = Math.abs(minutes);
  const hours = String(Math.floor(abs / 60)).padStart(2, "0");
  return `${minutes < 0 ? "−" : "+"}${hours}:${String(abs % 60).padStart(2, "0")}`;
}

function fold(text: string): string {
  return text
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[_/-]+/g, " ")
    .trim();
}

/** "+7", "+07:00", "UTC+7", "-3:30" to minutes; null when the token is not an offset. */
export function parseOffset(token: string): number | null {
  const match = /^(?:utc|gmt)?([+\-−])(\d{1,2})(?::?(\d{2}))?$/i.exec(token.replace(/\s+/g, ""));
  if (!match) return null;
  const hours = Number(match[2]);
  const minutes = Number(match[3] ?? 0);
  if (hours > 14 || minutes > 59) return null;
  return (match[1] === "+" ? 1 : -1) * (hours * 60 + minutes);
}

/** True when the zone matches every word of the query (id, city, alias) and every offset. */
export function zoneMatches(zone: string, query: string, at: number = Date.now()): boolean {
  const tokens = query
    .trim()
    .replace(/\b(utc|gmt)\s+(?=[+\-−])/gi, "$1")
    .replace(/([+\-−])\s+(?=\d)/g, "$1")
    .split(/\s+/)
    .filter(Boolean);
  if (tokens.length === 0) return true;
  const haystack = ` ${[zone, ...(ALIASES[zone] ?? [])].map(fold).join(" ")} `;
  for (const token of tokens) {
    const offset = parseOffset(token);
    if (offset !== null) {
      if (zoneOffset(zone, at) !== offset) return false;
      continue;
    }
    for (const word of fold(token).split(" ").filter(Boolean)) {
      if (!haystack.includes(` ${word}`) && !(word.length > 2 && haystack.includes(word))) {
        return false;
      }
    }
  }
  return true;
}
