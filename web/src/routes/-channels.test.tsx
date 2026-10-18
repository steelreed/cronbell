import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Channel } from "@/lib/types";
import { aChannel, SESSION, stubFetch } from "@/test/fixtures";
import { renderAt } from "@/test/render";

afterEach(() => vi.unstubAllGlobals());

type Routes = Parameters<typeof stubFetch>[0];

function signedIn(routes: Routes): Routes {
  return {
    "GET /api/v1/setup": () => [200, { required: false }],
    "GET /api/v1/session": () => [200, SESSION],
    "GET /api/v1/checks": () => [200, []],
    ...routes,
  };
}

const CHANNELS: Channel[] = [
  aChannel(),
  aChannel({
    id: "ch-pager",
    type: "ntfy",
    name: "Pager",
    config: { serverUrl: "https://ntfy.example.com", topic: "ops", token: "tk…9a" },
  }),
];

describe("Channels page", () => {
  it("adds an ntfy channel on ntfy.sh with only a topic", async () => {
    const created: unknown[] = [];
    vi.stubGlobal(
      "fetch",
      stubFetch(
        signedIn({
          "GET /api/v1/channels": () => [200, []],
          "POST /api/v1/channels": (init) => {
            created.push(JSON.parse(String(init?.body)));
            return [201, CHANNELS[1]];
          },
        }),
      ),
    );
    const user = userEvent.setup();
    renderAt("/channels");
    await user.click(await screen.findByRole("button", { name: "Add channel" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("radio", { name: "ntfy" }));
    expect(within(dialog).getByText(/Leave empty for ntfy\.sh/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Add channel" }));
    expect(within(dialog).getByText("Topic is required.")).toBeInTheDocument();

    await user.type(within(dialog).getByLabelText("Topic"), "cronbell-ops");
    await user.click(within(dialog).getByRole("button", { name: "Add channel" }));
    await waitFor(() =>
      expect(created).toEqual([{ type: "ntfy", config: { topic: "cronbell-ops" } }]),
    );
  });

  it("offers Discord with its own webhook field", async () => {
    vi.stubGlobal("fetch", stubFetch(signedIn({ "GET /api/v1/channels": () => [200, []] })));
    const user = userEvent.setup();
    renderAt("/channels");
    await user.click(await screen.findByRole("button", { name: "Add channel" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("radio", { name: "Discord" }));
    expect(within(dialog).getByLabelText("Webhook URL")).toHaveAttribute(
      "placeholder",
      "https://discord.com/api/webhooks/…",
    );
  });
});
