import type { Check, Session } from "@/lib/types";

export const NOW = Date.parse("2026-01-05T10:00:00Z");

export function aCheck(overrides: Partial<Check> = {}): Check {
  return {
    slug: "nightly-backup",
    name: "Nightly backup",
    pingKey: "0b7c3a52-5b8e-4a3a-9a52-2b8e8f9c6d11",
    pingUrl: "https://cron.example.com/ping/0b7c3a52-5b8e-4a3a-9a52-2b8e8f9c6d11",
    scheduleKind: "CRON",
    cron: "0 3 * * *",
    timeZone: "Asia/Ho_Chi_Minh",
    periodSeconds: null,
    graceSeconds: 1800,
    maxRuntimeSeconds: null,
    status: "UP",
    downReason: null,
    lastPingAt: "2026-01-04T20:01:00Z",
    lastStartAt: null,
    lastSuccessAt: "2026-01-04T20:01:00Z",
    nextDueAt: "2026-01-05T20:00:00Z",
    nextRunAt: "2026-01-05T20:00:00Z",
    createdAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

export const SESSION: Session = {
  account: { id: "a1", email: "admin@example.com", instanceAdmin: true },
  project: { id: "p1", name: "Default", role: "OWNER" },
  projects: [{ id: "p1", name: "Default", role: "OWNER" }],
  pingBaseUrl: "http://localhost/ping/",
};

/** A tester: member of someone else's project and owner of their own. */
export const MEMBER_SESSION: Session = {
  account: { id: "a2", email: "lan@example.com", instanceAdmin: false },
  project: { id: "p2", name: "Team jobs", role: "MEMBER" },
  projects: [
    { id: "p3", name: "lan's project", role: "OWNER" },
    { id: "p2", name: "Team jobs", role: "MEMBER" },
  ],
  pingBaseUrl: "http://localhost/ping/",
};

/** A fetch stub answering by "METHOD path". Unknown requests are 404. */
export function stubFetch(routes: Record<string, (init?: RequestInit) => [number, unknown?]>) {
  return async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), "http://localhost");
    const key = `${init?.method ?? "GET"} ${url.pathname}`;
    const handler = routes[key];
    const [status, body] = handler ? handler(init) : [404, { detail: `no stub for ${key}` }];
    return new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  };
}
