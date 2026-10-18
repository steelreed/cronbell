import { describe, expect, it } from "vitest";
import { aCheck } from "@/test/fixtures";
import { firstSteps, setUp, waitingCheck } from "./first-steps";
import type { Member } from "./types";

const owner: Member = {
  accountId: "a1",
  email: "admin@example.com",
  role: "OWNER",
  joinedAt: "2026-01-01T00:00:00Z",
  you: true,
};
const teammate: Member = { ...owner, accountId: "a2", email: "lan@example.com", you: false };

const done = (steps: ReturnType<typeof firstSteps>) =>
  Object.fromEntries(steps.map((step) => [step.id, step.done]));

describe("firstSteps", () => {
  it("starts with nothing done", () => {
    const steps = firstSteps([], [], [owner]);
    expect(done(steps)).toEqual({ create: false, ping: false, channel: false, invite: false });
    expect(setUp(steps)).toBe(false);
  });

  it("counts a check, its first ping and a channel", () => {
    const unpinged = aCheck({ status: "NEW", lastPingAt: null });
    expect(done(firstSteps([unpinged], [], null))).toEqual({
      create: true,
      ping: false,
      channel: false,
    });
    const steps = firstSteps([unpinged, aCheck()], [{ id: "c1" }], null);
    expect(done(steps)).toEqual({ create: true, ping: true, channel: true });
    expect(setUp(steps)).toBe(true);
  });

  it("offers inviting a teammate to owners only, and never waits for it", () => {
    expect(firstSteps([], [], null).map((step) => step.id)).not.toContain("invite");
    const steps = firstSteps([aCheck()], [{ id: "c1" }], [owner]);
    expect(steps.at(-1)).toEqual({ id: "invite", done: false, optional: true });
    expect(setUp(steps)).toBe(true);
    expect(firstSteps([], [], [owner, teammate]).at(-1)?.done).toBe(true);
  });
});

describe("waitingCheck", () => {
  it("picks the oldest check never pinged", () => {
    const older = aCheck({ slug: "a", lastPingAt: null, createdAt: "2026-01-02T00:00:00Z" });
    const newer = aCheck({ slug: "b", lastPingAt: null, createdAt: "2026-01-03T00:00:00Z" });
    expect(waitingCheck([aCheck(), newer, older])?.slug).toBe("a");
    expect(waitingCheck([aCheck()])).toBeNull();
  });
});
