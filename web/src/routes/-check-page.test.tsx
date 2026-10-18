import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Ping } from "@/lib/types";
import { aCheck, anAlert, anEvent, SESSION, stubFetch } from "@/test/fixtures";
import { renderAt } from "@/test/render";

afterEach(() => vi.unstubAllGlobals());

const ping = (id: string, kind: Ping["kind"], receivedAt: string, extra: Partial<Ping> = {}) => ({
  id,
  kind,
  exitCode: null,
  rid: null,
  body: null,
  receivedAt,
  remoteAddr: "10.0.4.12",
  userAgent: "curl/8.7.1",
  durationMs: null,
  ...extra,
});

describe("check page", () => {
  it("shows uptime, the history with its alerts and the runs, raw pings on demand", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch({
        "GET /api/v1/setup": () => [200, { required: false }],
        "GET /api/v1/session": () => [200, SESSION],
        "GET /api/v1/checks/nightly-backup": () => [
          200,
          aCheck({
            status: "DOWN",
            downReason: "FAILED",
            stats: { uptime7d: 0.9583, uptime30d: 0.99, averageDurationMs7d: 70_000 },
          }),
        ],
        "GET /api/v1/checks/nightly-backup/pings": () => [
          200,
          [
            ping("p3", "FAIL", "2026-01-04T20:01:10Z", {
              exitCode: 3,
              body: "disk full",
              durationMs: 70_000,
            }),
            ping("p2", "START", "2026-01-04T20:00:00Z"),
            ping("p1", "SUCCESS", "2026-01-03T20:01:00Z"),
          ],
        ],
        "GET /api/v1/checks/nightly-backup/events": () => [
          200,
          [anEvent("e1", "DOWN", "2026-01-04T20:01:10Z", { reason: "FAILED" })],
        ],
        "GET /api/v1/checks/nightly-backup/alerts": () => [
          200,
          [anAlert("a1", "e1", "Telegram", "DELIVERED")],
        ],
      }),
    );
    renderAt("/checks/nightly-backup");
    expect(await screen.findByRole("heading", { name: "Nightly backup" })).toBeInTheDocument();
    expect((await screen.findByText("Uptime, 7 days")).nextElementSibling).toHaveTextContent(
      "95.8%",
    );
    expect(screen.getByText("Average run, 7 days").nextElementSibling).toHaveTextContent("1m 10s");
    expect(await screen.findByText("still down")).toBeInTheDocument();
    expect(screen.getByText("Telegram")).toBeInTheDocument();

    expect(await screen.findByRole("heading", { name: "Recent runs" })).toBeInTheDocument();
    expect(screen.getAllByText("failed · exit 3").length).toBeGreaterThan(0);
    expect(screen.getAllByText("1m 10s").length).toBeGreaterThan(1);

    await userEvent.click(screen.getByRole("button", { name: "Raw pings" }));
    expect(screen.getByRole("heading", { name: "Recent pings" })).toBeInTheDocument();
    expect(screen.getAllByText("start").length).toBeGreaterThan(0);
  });
});
