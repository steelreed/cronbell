import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { CheckDays, DayState, StatusEvent } from "@/lib/types";
import { aCheck, anEvent, SESSION, stubFetch } from "@/test/fixtures";
import { renderAt } from "@/test/render";

afterEach(() => vi.unstubAllGlobals());

/** A check's strip: 30 days, all fine but the last ones given. */
function strip(name: string, ...last: DayState[]): CheckDays {
  const states: DayState[] = [...Array(30 - last.length).fill("OK"), ...last];
  return {
    slug: name.toLowerCase().replaceAll(" ", "-"),
    name,
    days: states.map((state, i) => ({
      date: `2026-01-${String(i + 1).padStart(2, "0")}`,
      state,
      runs: state === "NONE" ? 0 : 24,
      failures: state === "ISSUES" ? 1 : 0,
      missed: state === "DOWN" ? 1 : 0,
      downMs: state === "DOWN" ? 3_600_000 : 0,
    })),
  };
}

const iso = (msAgo: number) => new Date(Date.now() - msAgo).toISOString();
const HOUR = 3_600_000;

function stubOverview(checks: CheckDays[], events: StatusEvent[]) {
  vi.stubGlobal(
    "fetch",
    stubFetch({
      "GET /api/v1/setup": () => [200, { required: false }],
      "GET /api/v1/session": () => [200, SESSION],
      "GET /api/v1/checks": () => [
        200,
        checks.map((check) =>
          aCheck({
            slug: check.slug,
            name: check.name,
            lastPingAt: iso(HOUR),
            nextDueAt: iso(-HOUR),
            nextRunAt: iso(-HOUR),
          }),
        ),
      ],
      "GET /api/v1/stats/status": () => [200, checks],
      "GET /api/v1/events": () => [200, events],
    }),
  );
}

describe("overview", () => {
  it("shows twelve checks' last 30 days, problems first, and the rest on demand", async () => {
    const quiet = Array.from({ length: 12 }, (_, i) =>
      strip(`Job ${String(i + 1).padStart(2, "0")}`),
    );
    stubOverview(
      [...quiet, strip("Flaky export", "ISSUES"), strip("Nightly backup", "OK", "DOWN")],
      [],
    );
    renderAt("/");

    const rows = await screen.findAllByRole("img", { name: /good days?,/ });
    expect(rows).toHaveLength(12);
    expect(rows[0]).toHaveAccessibleName("Nightly backup: 29 good days, 0 with trouble, 1 down");
    expect(rows[1]).toHaveAccessibleName("Flaky export: 29 good days, 1 with trouble, 0 down");
    const cells = rows[0].querySelectorAll("[data-state]");
    expect(cells).toHaveLength(30);
    expect(cells[29]).toHaveAttribute("data-state", "DOWN");
    expect(cells[29].getAttribute("title")).toMatch(
      /· 24 runs, 0 failed · missed 1 run · down 1h$/,
    );
    expect(screen.queryByRole("img", { name: /^Job 12:/ })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Show all 14 checks" }));
    expect(screen.getAllByRole("img", { name: /good days?,/ })).toHaveLength(14);
    expect(screen.getByRole("img", { name: /^Job 12:/ })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Show fewer" }));
    expect(screen.getAllByRole("img", { name: /good days?,/ })).toHaveLength(12);
  });

  it("lists the recent status changes with why and for how long", async () => {
    stubOverview(
      [strip("Nightly backup")],
      [
        anEvent("e4", "UP", iso(1 * HOUR)),
        anEvent("e3", "DOWN", iso(4 * HOUR), { reason: "FAILED" }),
        anEvent("e2", "UP", iso(20 * HOUR), { slug: "report", name: "Weekly report" }),
        anEvent("e1", "PAUSED", iso(30 * HOUR), { slug: "report", name: "Weekly report" }),
      ],
    );
    renderAt("/");

    await screen.findByText(/recovered after/);
    const feed = screen
      .getByRole("heading", { name: "Recent events" })
      .closest("section") as HTMLElement;
    const items = within(feed).getAllByRole("listitem");
    expect(items).toHaveLength(4);
    expect(items[0]).toHaveTextContent(/Nightly backup.*up.*recovered after 3h/);
    expect(items[1]).toHaveTextContent(/Nightly backup.*down.*run failed/);
    expect(items[2]).toHaveTextContent(/Weekly report.*up.*pinged after a pause/);
    expect(within(items[0]).getByRole("link")).toHaveAttribute("href", "/checks/nightly-backup");
  });

  it("says when nothing changed yet", async () => {
    stubOverview([strip("Nightly backup")], []);
    renderAt("/");
    expect(await screen.findByText("No status changes yet.")).toBeInTheDocument();
    expect(screen.getByText("Every check is up or paused.")).toBeInTheDocument();
  });
});
