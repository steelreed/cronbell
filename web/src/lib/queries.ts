import { queryOptions } from "@tanstack/react-query";
import { ApiError, api } from "./api";
import type { ApiKey, Channel, Check, Ping, Session, SetupStatus } from "./types";

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

export const pingsQuery = (slug: string) =>
  queryOptions({
    queryKey: ["checks", slug, "pings"],
    queryFn: () => api<Ping[]>(`/checks/${encodeURIComponent(slug)}/pings?limit=50`),
    refetchInterval: POLL_MS,
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
