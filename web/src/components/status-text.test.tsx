import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { aCheck, NOW } from "@/test/fixtures";
import { StatusText } from "./status-text";

describe("StatusText", () => {
  it("renders DOWN as red text with its reason, not a badge", () => {
    render(<StatusText check={aCheck({ status: "DOWN", downReason: "FAILED" })} now={NOW} />);
    const label = screen.getByText("down");
    expect(label).toHaveClass("text-problem");
    expect(screen.getByText("run failed")).toBeInTheDocument();
    expect(label.className).not.toMatch(/border|rounded-full|bg-/);
  });

  it("renders UP in green", () => {
    render(<StatusText check={aCheck()} now={NOW} />);
    expect(screen.getByText("up")).toHaveClass("text-ok");
  });
});

describe("StatusText late", () => {
  it("renders a late check in the warning tone with its reason", () => {
    render(<StatusText check={aCheck({ nextDueAt: "2026-01-05T09:00:00Z" })} now={NOW} />);
    expect(screen.getByText("late")).toHaveClass("text-warn");
    expect(screen.getByText("within grace")).toBeInTheDocument();
  });
});
