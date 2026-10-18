// Shapes of the management API (DESIGN.md §6). Times are ISO-8601 strings.

export type CheckStatus = "NEW" | "UP" | "DOWN" | "PAUSED";
export type DownReason = "MISSED" | "FAILED" | "RUNNING_TOO_LONG";
export type ScheduleKind = "CRON" | "PERIOD";
export type PingKind = "START" | "SUCCESS" | "FAIL" | "LOG";
export type ChannelType = "email" | "telegram" | "slack" | "webhook";

export interface Check {
  slug: string;
  name: string;
  pingKey: string;
  pingUrl: string;
  scheduleKind: ScheduleKind;
  cron: string | null;
  timeZone: string;
  periodSeconds: number | null;
  graceSeconds: number;
  maxRuntimeSeconds: number | null;
  status: CheckStatus;
  downReason: DownReason | null;
  lastPingAt: string | null;
  lastStartAt: string | null;
  lastSuccessAt: string | null;
  nextDueAt: string | null;
  /** When the schedule next expects a run; past nextDueAt for a late or missed run. */
  nextRunAt: string | null;
  createdAt: string;
  /** Only from GET /checks/{slug}. */
  stats?: CheckStats | null;
}

/** Uptime is a share from 0 to 1, paused time left out; null when not known. */
export interface CheckStats {
  uptime7d: number | null;
  uptime30d: number | null;
  averageDurationMs7d: number | null;
}

export type StatusEventKind = "UP" | "DOWN" | "PAUSED" | "RESUMED";

/** A status change: the status entered, and for a DOWN why and the run it is about. */
export interface StatusEvent {
  id: string;
  slug: string;
  name: string;
  kind: StatusEventKind;
  reason: DownReason | null;
  runAt: string | null;
  at: string;
}

export type DeliveryStatus = "PENDING" | "DELIVERED" | "FAILED";

/** One alert of a check to one channel, for the status event that raised it. */
export interface CheckAlert {
  id: string;
  eventId: string | null;
  channelId: string;
  channelName: string;
  channelType: ChannelType;
  status: DeliveryStatus;
  lastError: string | null;
  createdAt: string;
  deliveredAt: string | null;
}

/** How a check spent a day: DOWN at its end, DOWN for a while or a failure, fine, or no data. */
export type DayState = "DOWN" | "ISSUES" | "OK" | "NONE";

export interface StatusDay {
  date: string;
  state: DayState;
  runs: number;
  failures: number;
  /** How often the check went DOWN for a missed run that day. */
  missed: number;
  downMs: number;
}

/** One check of GET /stats/status, its days oldest first. */
export interface CheckDays {
  slug: string;
  name: string;
  days: StatusDay[];
}

export interface CheckDefinition {
  name?: string;
  cron?: string;
  periodSeconds?: number;
  timeZone?: string;
  graceSeconds?: number;
  maxRuntimeSeconds?: number;
}

export interface Ping {
  id: string;
  kind: PingKind;
  exitCode: number | null;
  rid: string | null;
  body: string | null;
  receivedAt: string;
  remoteAddr: string | null;
  userAgent: string | null;
  durationMs: number | null;
}

export interface Channel {
  id: string;
  type: ChannelType;
  name: string;
  config: Record<string, string>;
  createdAt: string;
  updatedAt: string;
  lastAttemptAt: string | null;
  lastDeliveryAt: string | null;
  lastError: string | null;
  pendingDeliveries: number;
}

export interface ApiKey {
  id: string;
  name: string;
  prefix: string;
  createdAt: string;
}

export interface NewApiKey extends ApiKey {
  key: string;
}

export type Role = "OWNER" | "MEMBER";

/** A project of the signed-in account, with its role there. */
export interface ProjectRef {
  id: string;
  name: string;
  role: Role;
}

export interface Session {
  account: { id: string; email: string; instanceAdmin: boolean };
  /** The current project; null when the account is in no project. */
  project: ProjectRef | null;
  /** Every project of the account, oldest membership first. */
  projects: ProjectRef[];
  pingBaseUrl: string;
}

export interface Member {
  accountId: string;
  email: string;
  role: Role;
  joinedAt: string;
  /** Whether this is the signed-in account. */
  you: boolean;
}

export type InviteKind = "PROJECT" | "MEMBER";
export type InviteStatus = "PENDING" | "ACCEPTED" | "EXPIRED";

export interface Invite {
  id: string;
  kind: InviteKind;
  status: InviteStatus;
  note: string | null;
  /** The project joined, or the project made when a project invite was accepted. */
  projectName: string | null;
  createdBy: string;
  createdAt: string;
  expiresAt: string;
  acceptedBy: string | null;
  acceptedAt: string | null;
  /** The link, only in the answer to its creation. */
  url: string | null;
}

/** What an invite link offers (GET /invites/{token}). */
export interface InviteOffer {
  kind: InviteKind;
  /** The project a member invite joins; null for a project invite. */
  projectName: string | null;
  invitedBy: string;
  expiresAt: string;
}

export interface SetupStatus {
  required: boolean;
}

/** A finished run from GET /runs/recent: a success or fail ping. */
export interface RecentRun {
  at: string;
  ok: boolean;
  exitCode: number | null;
}

/** Slug to its latest finished runs, oldest first. */
export type RecentRuns = Record<string, RecentRun[]>;

export interface NextRuns {
  cron: string;
  timeZone: string;
  runs: string[];
}
