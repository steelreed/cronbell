import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { anAlert, anEvent } from "@/test/fixtures";
import { HistoryPanel } from "./history-panel";

const at = (day: number, hour: number, minute = 0) =>
  new Date(2026, 0, day, hour, minute).toISOString();

describe("HistoryPanel", () => {
  it("tells each downtime: why, from when to when, how long, and where the alerts went", () => {
    render(
      <HistoryPanel
        events={[
          anEvent("2", "UP", at(3, 8, 1)),
          anEvent("1", "DOWN", at(3, 2, 15), { reason: "MISSED", runAt: at(3, 2) }),
        ]}
        alerts={[
          anAlert("a2", "1", "Email", "FAILED"),
          anAlert("a1", "1", "Telegram", "DELIVERED"),
        ]}
        now={new Date(2026, 0, 5, 9).getTime()}
      />,
    );
    expect(screen.getByText("missed the 3 Jan 02:00 run")).toBeInTheDocument();
    expect(screen.getByText("recovered 3 Jan 08:01")).toBeInTheDocument();
    expect(screen.getByText(/\(5h 46m\)/)).toBeInTheDocument();
    const alerted = (text: string) =>
      screen.getByText((_, element) => element?.tagName === "SPAN" && element.textContent === text);
    expect(alerted("Telegram ✓delivered")).toBeInTheDocument();
    expect(alerted("Email ✗failed")).toHaveAttribute("title", "connection refused");
  });

  it("says when a check is still down or paused", () => {
    render(
      <HistoryPanel
        events={[
          anEvent("2", "PAUSED", at(5, 8)),
          anEvent("1", "DOWN", at(5, 6), { reason: "FAILED" }),
        ]}
        alerts={[]}
        now={new Date(2026, 0, 5, 9).getTime()}
      />,
    );
    expect(screen.getByText("still paused")).toBeInTheDocument();
    expect(screen.getByText("paused 08:00")).toBeInTheDocument();
    expect(screen.getByText("run failed")).toBeInTheDocument();
    expect(screen.queryByText("Alerted")).not.toBeInTheDocument();
  });

  it("has a calm line when nothing happened", () => {
    render(<HistoryPanel events={[anEvent("1", "UP", at(1, 0))]} alerts={[]} now={0} />);
    expect(screen.getByText("Nothing to show: no downtime and no pauses.")).toBeInTheDocument();
  });
});
