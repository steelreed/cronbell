import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { TooltipProvider } from "@/components/ui/tooltip";
import { aCheck } from "@/test/fixtures";
import { CheckForm } from "./check-form";

function renderForm(props: Partial<Parameters<typeof CheckForm>[0]> = {}) {
  const onSubmit = vi.fn();
  render(
    <TooltipProvider>
      <CheckForm pending={false} error={null} onSubmit={onSubmit} onCancel={() => {}} {...props} />
    </TooltipProvider>,
  );
  return onSubmit;
}

describe("CheckForm", () => {
  it("shows errors instead of submitting an invalid check", async () => {
    const user = userEvent.setup();
    const onSubmit = renderForm();
    await user.type(screen.getByLabelText("Slug"), "bad slug!");
    const cron = screen.getByLabelText("Cron");
    await user.clear(cron);
    await user.type(cron, "every night");
    await user.click(screen.getByRole("button", { name: "Create check" }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByText(/Use 1-128 letters/)).toBeInTheDocument();
    expect(screen.getByText(/5 or 6 fields/)).toBeInTheDocument();
    expect(screen.getByLabelText("Slug")).toHaveAttribute("aria-invalid", "true");
  });

  it("submits a period schedule in seconds", async () => {
    const user = userEvent.setup();
    const onSubmit = renderForm();
    await user.type(screen.getByLabelText("Slug"), "queue-worker");
    await user.click(screen.getByRole("tab", { name: "Every" }));
    const period = screen.getByLabelText("Every");
    await user.clear(period);
    await user.type(period, "15");
    await user.selectOptions(screen.getAllByLabelText("Unit")[0], "minutes");
    const zone = screen.getByLabelText("Time zone");
    await user.clear(zone);
    await user.type(zone, "UTC");
    await user.click(screen.getByRole("button", { name: "Create check" }));
    expect(onSubmit).toHaveBeenCalledWith("queue-worker", {
      periodSeconds: 900,
      timeZone: "UTC",
      graceSeconds: 1800,
    });
  });

  it("keeps the slug of an existing check", () => {
    renderForm({ check: aCheck({ maxRuntimeSeconds: 600 }) });
    expect(screen.getByLabelText("Slug")).toBeDisabled();
    expect(screen.getByLabelText("Slug")).toHaveValue("nightly-backup");
    expect(screen.getByLabelText("Max runtime")).toHaveValue(10);
  });
});
