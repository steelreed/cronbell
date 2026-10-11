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
  createdAt: string;
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

export interface Session {
  account: { id: string; email: string };
  project: { id: string; name: string | null };
  pingBaseUrl: string;
}

export interface SetupStatus {
  required: boolean;
}
