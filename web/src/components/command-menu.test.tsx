import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from "@tanstack/react-router";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { aCheck, stubFetch } from "@/test/fixtures";
import { CommandMenu, useCommandShortcut } from "./command-menu";

afterEach(() => vi.unstubAllGlobals());

function Harness() {
  const [open, setOpen] = useState(false);
  useCommandShortcut(() => setOpen(true));
  return (
    <>
      <Outlet />
      <CommandMenu open={open} onOpenChange={setOpen} />
    </>
  );
}

function renderMenu() {
  vi.stubGlobal(
    "fetch",
    stubFetch({
      "GET /api/v1/checks": () => [
        200,
        [
          aCheck(),
          aCheck({
            slug: "queue-worker",
            name: "Queue worker",
            status: "DOWN",
            downReason: "FAILED",
          }),
        ],
      ],
    }),
  );
  const root = createRootRoute({ component: Harness });
  const page = (path: string) =>
    createRoute({ getParentRoute: () => root, path, component: () => <p>{path}</p> });
  const router = createRouter({
    routeTree: root.addChildren([
      page("/"),
      page("/checks/$slug"),
      page("/checks/new"),
      page("/channels"),
    ]),
    history: createMemoryHistory({ initialEntries: ["/"] }),
  });
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

describe("CommandMenu", () => {
  it("opens with ⌘K and jumps to a check found by its slug", async () => {
    const user = userEvent.setup();
    const router = renderMenu();
    await screen.findByText("/");
    await user.keyboard("{Meta>}k{/Meta}");
    const input = await screen.findByPlaceholderText("Search checks and pages");
    await screen.findByText("Queue worker");
    await user.type(input, "queue-w");
    expect(screen.queryByText("Nightly backup")).not.toBeInTheDocument();
    await user.keyboard("{Enter}");
    await waitFor(() => expect(router.state.location.pathname).toBe("/checks/queue-worker"));
    expect(screen.queryByPlaceholderText("Search checks and pages")).not.toBeInTheDocument();
  });

  it("goes to pages and starts a new check", async () => {
    const user = userEvent.setup();
    const router = renderMenu();
    await screen.findByText("/");
    await user.keyboard("/");
    await user.type(await screen.findByPlaceholderText("Search checks and pages"), "new check");
    await user.keyboard("{Enter}");
    await waitFor(() => expect(router.state.location.pathname).toBe("/checks/new"));
  });
});
