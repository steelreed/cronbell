import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Check, Ping } from "@/lib/types";
import { aCheck, SESSION, stubFetch } from "@/test/fixtures";
import { renderAt } from "@/test/render";

afterEach(() => vi.unstubAllGlobals());

type Routes = Parameters<typeof stubFetch>[0];

const signedIn: Routes = {
  "GET /api/v1/setup": () => [200, { required: false }],
  "GET /api/v1/session": () => [200, SESSION],
  "GET /api/v1/stats/daily": () => [200, []],
  "GET /api/v1/members": () => [200, [{ accountId: "a1", email: "admin@example.com" }]],
};

const fresh = aCheck({
  status: "NEW",
  lastPingAt: null,
  lastSuccessAt: null,
  nextDueAt: null,
});

const aPing: Ping = {
  id: "p1",
  kind: "SUCCESS",
  exitCode: null,
  rid: null,
  body: "Test ping from the Cronbell dashboard",
  receivedAt: "2026-01-05T09:59:58Z",
  remoteAddr: "::1",
  userAgent: null,
  durationMs: null,
};

const channel = (lastDeliveryAt: string | null) => ({
  id: "c1",
  type: "EMAIL",
  name: "Ops",
  lastDeliveryAt,
});

async function findSteps() {
  const heading = await screen.findByRole("heading", { name: "Get started" });
  return heading.closest("section") as HTMLElement;
}

describe("first steps on the overview", () => {
  it("opens the next step and links to the check waiting for its ping", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch({
        ...signedIn,
        "GET /api/v1/checks": () => [200, [fresh]],
        "GET /api/v1/channels": () => [200, []],
      }),
    );
    renderAt("/");
    const steps = await findSteps();
    expect(within(steps).getByText("1 of 3 done")).toBeInTheDocument();
    const current = within(steps)
      .getAllByRole("listitem")
      .find((item) => item.getAttribute("aria-current") === "step");
    expect(current).toHaveTextContent("Receive the first ping");
    expect(within(steps).getByRole("link", { name: "Open Nightly backup" })).toHaveAttribute(
      "href",
      "/checks/nightly-backup",
    );
    expect(within(steps).getByText(/Invite a teammate/)).toBeInTheDocument();
  });

  it("asks for a test once a channel exists but has delivered nothing", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch({
        ...signedIn,
        "GET /api/v1/checks": () => [200, [aCheck()]],
        "GET /api/v1/channels": () => [200, [channel(null)]],
      }),
    );
    renderAt("/");
    const steps = await findSteps();
    expect(within(steps).getByText("2 of 3 done")).toBeInTheDocument();
    expect(within(steps).getByRole("link", { name: "Send a test" })).toHaveAttribute(
      "href",
      "/channels",
    );
  });

  it("goes away once a check has pinged and a channel has delivered", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch({
        ...signedIn,
        "GET /api/v1/checks": () => [200, [aCheck()]],
        "GET /api/v1/channels": () => [200, [channel("2026-01-05T09:00:00Z")]],
      }),
    );
    renderAt("/");
    expect(await screen.findByText("Up")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Get started" })).not.toBeInTheDocument();
  });
});

describe("a check waiting for its first ping", () => {
  it("leads with the ping URL, sends a test ping and shows it arrive", async () => {
    let check: Check = fresh;
    let pings: Ping[] = [];
    const ping = vi.fn(() => {
      check = {
        ...fresh,
        status: "UP",
        lastPingAt: aPing.receivedAt,
        lastSuccessAt: aPing.receivedAt,
      };
      pings = [aPing];
      return [200] as [number];
    });
    vi.stubGlobal(
      "fetch",
      stubFetch({
        ...signedIn,
        "GET /api/v1/checks": () => [200, [check]],
        "GET /api/v1/checks/nightly-backup": () => [200, check],
        "GET /api/v1/checks/nightly-backup/pings": () => [200, pings],
        [`POST /ping/${fresh.pingKey}`]: ping,
      }),
    );
    renderAt("/checks/nightly-backup");
    expect(await screen.findByText("Waiting for the first ping…")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Send pings" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Runs" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Recent pings" })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Send a test ping" }));
    expect(await screen.findByText("First ping received")).toBeInTheDocument();
    expect(ping).toHaveBeenCalledOnce();
    expect(await screen.findByRole("heading", { name: "Recent pings" })).toBeInTheDocument();
    const headings = screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent);
    expect(headings.indexOf("Send pings")).toBeLessThan(headings.indexOf("Runs"));
  });

  it("says when the ping was refused", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch({
        ...signedIn,
        "GET /api/v1/checks": () => [200, [fresh]],
        "GET /api/v1/checks/nightly-backup": () => [200, fresh],
        "GET /api/v1/checks/nightly-backup/pings": () => [200, []],
        [`POST /ping/${fresh.pingKey}`]: () => [429],
      }),
    );
    renderAt("/checks/nightly-backup");
    await userEvent.click(await screen.findByRole("button", { name: "Send a test ping" }));
    expect(await screen.findByText(/Too many pings at once/)).toBeInTheDocument();
    expect(screen.getByText("Waiting for the first ping…")).toBeInTheDocument();
  });

  it("keeps the usual layout for a check that has pinged", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch({
        ...signedIn,
        "GET /api/v1/checks": () => [200, [aCheck()]],
        "GET /api/v1/checks/nightly-backup": () => [200, aCheck()],
        "GET /api/v1/checks/nightly-backup/pings": () => [200, [aPing]],
      }),
    );
    renderAt("/checks/nightly-backup");
    expect(await screen.findByRole("heading", { name: "Recent pings" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Send a test ping" })).not.toBeInTheDocument();
    const headings = screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent);
    expect(headings.indexOf("Runs")).toBeLessThan(headings.indexOf("Send pings"));
  });
});
