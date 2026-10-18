import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRouter, RouterProvider } from "@tanstack/react-router";
import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { routeTree } from "@/routeTree.gen";
import { aCheck, SESSION, stubFetch } from "@/test/fixtures";

afterEach(() => vi.unstubAllGlobals());

function renderAt(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createRouter({
    routeTree,
    context: { queryClient },
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

describe("auth guard", () => {
  it("sends a first visitor to setup", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch({
        "GET /api/v1/setup": () => [200, { required: true }],
        "GET /api/v1/session": () => [401, { detail: "not signed in" }],
      }),
    );
    const router = renderAt("/checks/nightly-backup");
    expect(await screen.findByRole("heading", { name: "Set up Cronbell" })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/setup");
  });

  it("sends a visitor without a session to sign-in and remembers the page", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch({
        "GET /api/v1/setup": () => [200, { required: false }],
        "GET /api/v1/session": () => [401, { detail: "not signed in" }],
      }),
    );
    const router = renderAt("/channels");
    expect(await screen.findByRole("heading", { name: "Sign in to Cronbell" })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/signin");
    expect(router.state.location.search).toEqual({ redirect: "/channels" });
  });

  it("shows the overview to a signed-in visitor and keeps them off the sign-in page", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch({
        "GET /api/v1/setup": () => [200, { required: false }],
        "GET /api/v1/session": () => [200, SESSION],
        "GET /api/v1/checks": () => [200, [aCheck({ status: "DOWN", downReason: "MISSED" })]],
        "GET /api/v1/stats/daily": () => [200, [{ date: "2026-01-05", runs: 3, failures: 1 }]],
      }),
    );
    const router = renderAt("/signin");
    await waitFor(() => expect(router.state.location.pathname).toBe("/"));
    expect(await screen.findByRole("heading", { name: "Overview" })).toBeInTheDocument();
    expect((await screen.findAllByText("Nightly backup")).length).toBeGreaterThan(0);
    expect(screen.getByText(/Missed the .* run/)).toBeInTheDocument();
  });

  it("lists checks with their status and run history", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch({
        "GET /api/v1/setup": () => [200, { required: false }],
        "GET /api/v1/session": () => [200, SESSION],
        "GET /api/v1/checks": () => [200, [aCheck({ status: "DOWN", downReason: "FAILED" })]],
        "GET /api/v1/runs/recent": () => [
          200,
          { "nightly-backup": [{ at: "2026-01-04T20:01:00Z", ok: false, exitCode: 3 }] },
        ],
      }),
    );
    renderAt("/checks?status=down");
    expect((await screen.findAllByText("Nightly backup")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("run failed").length).toBeGreaterThan(0);
    expect(screen.getByRole("radio", { name: /Down/ })).toHaveAttribute("aria-checked", "true");
    expect(
      (await screen.findAllByRole("img", { name: /1 run: 0 ok, 1 failed/ })).length,
    ).toBeGreaterThan(0);
  });

  it("shows how to add the first check when there is none", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch({
        "GET /api/v1/setup": () => [200, { required: false }],
        "GET /api/v1/session": () => [200, SESSION],
        "GET /api/v1/checks": () => [200, []],
      }),
    );
    renderAt("/");
    expect(await screen.findByRole("link", { name: "Create your first check" })).toHaveAttribute(
      "href",
      "/checks/new",
    );
    expect(screen.getByText("Using the API or the Spring Boot starter?")).toBeInTheDocument();
    expect(
      screen.getByText(/curl -X PUT http:\/\/localhost(:\d+)?\/api\/v1\/checks\/nightly-backup/),
    ).toBeInTheDocument();
  });
});
