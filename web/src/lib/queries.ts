import { queryOptions } from "@tanstack/react-query";
import { ApiError, api } from "./api";
import type {
  ApiKey,
  Channel,
  Check,
  DayStat,
  NextRuns,
  Ping,
  RecentRuns,
  Session,
  SetupStatus,
} from "./types";

/** The detector runs every 15 s (DESIGN.md §4), so polling faster shows nothing new. */
export const POLL_MS = 15_000;

export const setupQuery = queryOptions({
  queryKey: ["setup"],
  queryFn: () => api<SetupStatus>("/setup"),
  staleTime: 60_000,
});

export const sessionQuery = queryOptions({
  queryKey: ["session"],
  queryFn: async (): Promise<Session | null> => {
    try {
      return await api<Session>("/session");
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) return null;
      throw error;
    }
  },
  staleTime: 60_000,
});

export const checksQuery = queryOptions({
  queryKey: ["checks"],
  queryFn: () => api<Check[]>("/checks"),
  refetchInterval: POLL_MS,
});

export const checkQuery = (slug: string) =>
  queryOptions({
    queryKey: ["checks", slug],
    queryFn: () => api<Check>(`/checks/${encodeURIComponent(slug)}`),
    refetchInterval: POLL_MS,
  });

/** The detail page reads the most pings the API gives: enough for ~90 runs with their starts. */
export const PING_LIMIT = 200;

export const pingsQuery = (slug: string) =>
  queryOptions({
    queryKey: ["checks", slug, "pings"],
    queryFn: () => api<Ping[]>(`/checks/${encodeURIComponent(slug)}/pings?limit=${PING_LIMIT}`),
    refetchInterval: POLL_MS,
  });

/** Runs shown in each row of the checks list. */
export const LIST_RUNS = 20;

export const recentRunsQuery = queryOptions({
  queryKey: ["runs", LIST_RUNS],
  queryFn: () => api<RecentRuns>(`/runs/recent?limit=${LIST_RUNS}`),
  refetchInterval: POLL_MS,
});

export const dailyStatsQuery = (timeZone: string, days = 14) =>
  queryOptions({
    queryKey: ["stats", "daily", timeZone, days],
    queryFn: () => api<DayStat[]>(`/stats/daily?days=${days}&tz=${encodeURIComponent(timeZone)}`),
    refetchInterval: 60_000,
  });

export const nextRunsQuery = (cron: string, timeZone: string) =>
  queryOptions({
    queryKey: ["schedules", cron, timeZone],
    queryFn: () =>
      api<NextRuns>(
        `/schedules/next?cron=${encodeURIComponent(cron)}&timeZone=${encodeURIComponent(timeZone)}`,
      ),
    staleTime: 60_000,
    retry: false,
  });

export const channelsQuery = queryOptions({
  queryKey: ["channels"],
  queryFn: () => api<Channel[]>("/channels"),
  refetchInterval: POLL_MS,
});

export const keysQuery = queryOptions({
  queryKey: ["keys"],
  queryFn: () => api<ApiKey[]>("/keys"),
});
