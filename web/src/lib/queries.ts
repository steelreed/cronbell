import { queryOptions, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, api } from "./api";
import type {
  ApiKey,
  Channel,
  Check,
  CheckAlert,
  DayStat,
  Invite,
  InviteOffer,
  Member,
  NextRuns,
  Ping,
  RecentRuns,
  Session,
  SetupStatus,
  StatusEvent,
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

export const checkEventsQuery = (slug: string) =>
  queryOptions({
    queryKey: ["checks", slug, "events"],
    queryFn: () => api<StatusEvent[]>(`/checks/${encodeURIComponent(slug)}/events`),
    refetchInterval: POLL_MS,
  });

/** Enough for the alerts of every event the check page reads, to a few channels each. */
export const ALERT_LIMIT = 200;

export const checkAlertsQuery = (slug: string) =>
  queryOptions({
    queryKey: ["checks", slug, "alerts"],
    queryFn: () =>
      api<CheckAlert[]>(`/checks/${encodeURIComponent(slug)}/alerts?limit=${ALERT_LIMIT}`),
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

export const membersQuery = queryOptions({
  queryKey: ["members"],
  queryFn: () => api<Member[]>("/members"),
});

/** Member invites of the current project (owners only). */
export const invitesQuery = queryOptions({
  queryKey: ["invites"],
  queryFn: () => api<Invite[]>("/invites"),
});

/** Project invites of the instance (instance admin only). */
export const adminInvitesQuery = queryOptions({
  queryKey: ["admin", "invites"],
  queryFn: () => api<Invite[]>("/admin/invites"),
});

export const inviteOfferQuery = (token: string) =>
  queryOptions({
    queryKey: ["invite-offer", token],
    queryFn: () => api<InviteOffer>(`/invites/${encodeURIComponent(token)}`),
    retry: false,
    staleTime: 60_000,
  });

/** The signed-in session as cached; dashboard pages always have it (the _app guard loads it). */
export function useSession(): Session | null {
  return useQuery(sessionQuery).data ?? null;
}

/** Whether the signed-in account owns the current project. */
export function useIsOwner(): boolean {
  return useSession()?.project?.role === "OWNER";
}

/** Queries that do not depend on the current project; everything else is dropped on a switch. */
const ACCOUNT_QUERIES = new Set(["setup", "session", "invite-offer"]);

/**
 * After the session moved to another project (switch, accepted invite): keep the new session
 * and forget every project's data, so nothing of the previous project shows.
 */
export function useSessionChanged() {
  const queryClient = useQueryClient();
  return (session: Session) => {
    queryClient.removeQueries({
      predicate: (query) => !ACCOUNT_QUERIES.has(String(query.queryKey[0])),
    });
    queryClient.setQueryData(sessionQuery.queryKey, session);
  };
}
