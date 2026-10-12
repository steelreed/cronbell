import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TooltipProvider } from "@/components/ui/tooltip";
import { aCheck, stubFetch } from "@/test/fixtures";
import { CheckForm } from "./check-form";

afterEach(() => vi.unstubAllGlobals());

function renderForm(props: Partial<Parameters<typeof CheckForm>[0]> = {}) {
  const onSubmit = vi.fn();
  vi.stubGlobal(
    "fetch",
    stubFetch({
      "GET /api/v1/schedules/next": () => [
        200,
        { cron: "0 3 * * *", timeZone: "UTC", runs: ["2026-01-06T03:00:00Z"] },
      ],
    }),
  );
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <TooltipProvider>
        <CheckForm
          pending={false}
          error={null}
          onSubmit={onSubmit}
          onCancel={() => {}}
          {...props}
        />
      </TooltipProvider>
    </QueryClientProvider>,
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
    expect(screen.getByLabelText("Slug")).toHaveFocus();
  });

  it("submits a period schedule in seconds with the picked zone", async () => {
    const user = userEvent.setup();
    const onSubmit = renderForm();
    await user.type(screen.getByLabelText("Slug"), "queue-worker");
    await user.click(screen.getByRole("radio", { name: "Every" }));
    const period = screen.getByLabelText("Every");
    await user.clear(period);
    await user.type(period, "15");
    await user.click(screen.getAllByRole("combobox", { name: "Unit" })[0]);
    await user.click(await screen.findByRole("option", { name: "minutes" }));
    await user.click(screen.getByLabelText("Time zone"));
    await user.type(screen.getByLabelText(/Search zone/), "utc");
    await user.click(await screen.findByRole("option", { name: /^UTC/ }));
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

  it("previews the next runs of a cron schedule", async () => {
    renderForm({ check: aCheck({ timeZone: "UTC" }) });
    expect(await screen.findByText("Next runs")).toBeInTheDocument();
    expect(await screen.findByText(/6 Jan/)).toBeInTheDocument();
  });
});
