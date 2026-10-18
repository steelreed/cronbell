import type { Channel, Check, Member } from "./types";

export type FirstStepId = "create" | "ping" | "channel" | "invite";

export interface FirstStep {
  id: FirstStepId;
  done: boolean;
  optional: boolean;
}

/**
 * The setup of a young project, in order: a check, its first ping, an alert channel that has
 * delivered (a test or a real alert), and, for owners, a teammate. `members` is null when the
 * account cannot invite.
 */
export function firstSteps(
  checks: Pick<Check, "lastPingAt">[],
  channels: Pick<Channel, "lastDeliveryAt">[],
  members: Member[] | null,
): FirstStep[] {
  const steps: FirstStep[] = [
    { id: "create", done: checks.length > 0, optional: false },
    { id: "ping", done: checks.some((check) => check.lastPingAt !== null), optional: false },
    {
      id: "channel",
      done: channels.some((channel) => channel.lastDeliveryAt !== null),
      optional: false,
    },
  ];
  if (members) steps.push({ id: "invite", done: members.length > 1, optional: true });
  return steps;
}

/** Done once every step that is not optional is. */
export function setUp(steps: FirstStep[]): boolean {
  return steps.every((step) => step.done || step.optional);
}

/** The check to open for the first ping: the oldest one never pinged. */
export function waitingCheck<T extends Pick<Check, "lastPingAt" | "createdAt">>(
  checks: T[],
): T | null {
  return (
    checks
      .filter((check) => check.lastPingAt === null)
      .sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt))[0] ?? null
  );
}
