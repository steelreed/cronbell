import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { runTicks } from "@/lib/runs";
import { aCheck } from "@/test/fixtures";
import { RunBar } from "./run-bar";

describe("RunBar", () => {
  it("draws one tick per slot, coloured by outcome, with a summary for screen readers", () => {
    const check = aCheck({
      status: "DOWN",
      downReason: "MISSED",
      nextDueAt: "2026-01-05T20:00:00Z",
    });
    const ticks = runTicks(
      [
        { at: "2026-01-03T20:00:00Z", ok: true, exitCode: 0 },
        { at: "2026-01-04T20:00:00Z", ok: false, exitCode: 3 },
      ],
      check,
      5,
    );
    const { container } = render(<RunBar ticks={ticks} />);
    expect(screen.getByRole("img", { name: "3 runs: 1 ok, 2 failed" })).toBeInTheDocument();
    const kinds = [...container.querySelectorAll("[data-kind]")].map((el) =>
      el.getAttribute("data-kind"),
    );
    expect(kinds).toEqual(["empty", "empty", "ok", "fail", "missed"]);
    expect(container.querySelector('[data-kind="fail"]')).toHaveClass("bg-problem");
    expect(container.querySelector('[data-kind="ok"]')).toHaveClass("bg-ok");
    expect(container.querySelector('[data-kind="fail"]')?.getAttribute("title")).toMatch(
      /failed · exit 3/,
    );
  });
});
