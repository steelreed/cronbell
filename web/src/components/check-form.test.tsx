import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TooltipProvider } from "@/components/ui/tooltip";
import { aChannel, aCheck, stubFetch } from "@/test/fixtures";
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
      "GET /api/v1/channels": () => [
        200,
        [
          aChannel(),
          aChannel({ id: "ch-pager", type: "ntfy", name: "Pager", config: { topic: "ops" } }),
        ],
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
    await user.type(screen.getByLabelText(/^Slug/), "bad slug!");
    const cron = screen.getByLabelText("Cron");
    await user.clear(cron);
    await user.type(cron, "every night");
    await user.click(screen.getByRole("button", { name: "Create check" }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByText(/Enter a slug/)).toBeInTheDocument();
    expect(screen.getByText(/5 or 6 fields/)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Slug/)).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText(/^Slug/)).toHaveFocus();
  });

  it("submits a period schedule in seconds with the picked zone", async () => {
    const user = userEvent.setup();
    const onSubmit = renderForm();
    await user.type(screen.getByLabelText(/^Slug/), "queue-worker");
    await user.click(screen.getByRole("radio", { name: "Every" }));
    const period = screen.getByLabelText("Every");
    await user.clear(period);
    await user.type(period, "15");
    await user.click(screen.getAllByRole("combobox", { name: "Unit" })[0]);
    await user.click(await screen.findByRole("option", { name: "minutes" }));
    await user.click(screen.getByLabelText("Time zone"));
    // Paste rather than type: each keystroke filters some 400 zones, which is slow on CI.
    await user.click(screen.getByLabelText(/Search zone/));
    await user.paste("utc");
    await user.click(await screen.findByRole("option", { name: /^UTC/ }));
    await user.click(screen.getByRole("button", { name: "Create check" }));
    expect(onSubmit).toHaveBeenCalledWith("queue-worker", {
      periodSeconds: 900,
      timeZone: "UTC",
      graceSeconds: 1800,
      channels: null,
    });
  }, 15_000);

  it("sends alerts to the ticked channels only", async () => {
    const user = userEvent.setup();
    const onSubmit = renderForm();
    await user.type(screen.getByLabelText(/^Slug/), "nightly-backup");
    await user.click(screen.getByRole("radio", { name: "Chosen channels" }));
    const first = await screen.findByRole("checkbox", { name: /Ops chat/ });
    await user.click(screen.getByRole("button", { name: "Create check" }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByText(/Tick at least one channel/)).toBeInTheDocument();
    expect(first).toHaveFocus();

    await user.click(screen.getByRole("checkbox", { name: /Pager/ }));
    await user.click(screen.getByRole("button", { name: "Create check" }));
    expect(onSubmit).toHaveBeenCalledWith(
      "nightly-backup",
      expect.objectContaining({ channels: ["ch-pager"] }),
    );
  });

  it("shows the channels an existing check picked", async () => {
    renderForm({ check: aCheck({ channels: ["ch-ops"] }) });
    expect(screen.getByRole("radio", { name: "Chosen channels" })).toBeChecked();
    expect(await screen.findByRole("checkbox", { name: /Ops chat/ })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /Pager/ })).not.toBeChecked();
  });

  it("makes the slug from the name until the slug is edited", async () => {
    const user = userEvent.setup();
    renderForm();
    await user.type(screen.getByLabelText("Name"), "Nightly DB backup");
    expect(screen.getByLabelText(/^Slug/)).toHaveValue("nightly-db-backup");
    await user.clear(screen.getByLabelText(/^Slug/));
    await user.type(screen.getByLabelText(/^Slug/), "db-backup");
    await user.type(screen.getByLabelText("Name"), " v2");
    expect(screen.getByLabelText(/^Slug/)).toHaveValue("db-backup");
    expect(screen.getByLabelText(/^Slug/)).toBeRequired();
  });

  it("shows a legacy zone of an existing check under its current name", () => {
    renderForm({ check: aCheck({ timeZone: "Asia/Saigon" }) });
    expect(screen.getByLabelText("Time zone")).toHaveTextContent("Asia/Ho_Chi_Minh");
    expect(screen.getByLabelText("Time zone")).toHaveTextContent("Vietnam");
  });

  it("keeps the slug of an existing check", () => {
    renderForm({ check: aCheck({ maxRuntimeSeconds: 600 }) });
    expect(screen.getByLabelText(/^Slug/)).toBeDisabled();
    expect(screen.getByLabelText(/^Slug/)).toHaveValue("nightly-backup");
    expect(screen.getByLabelText("Max runtime")).toHaveValue(10);
  });

  it("previews the next runs of a cron schedule", async () => {
    renderForm({ check: aCheck({ timeZone: "UTC" }) });
    expect(await screen.findByText("Next runs")).toBeInTheDocument();
    expect(await screen.findByText(/6 Jan/)).toBeInTheDocument();
  });
});
