import { screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SESSION, stubFetch } from "@/test/fixtures";
import { renderAt } from "@/test/render";

afterEach(() => vi.unstubAllGlobals());

const signedIn = {
  "GET /api/v1/setup": () => [200, { required: false }] as [number, unknown],
  "GET /api/v1/session": () => [200, SESSION] as [number, unknown],
  "GET /api/v1/checks": () => [200, []] as [number, unknown],
};

describe("not found", () => {
  it("shows an unknown page inside the app, with a way back", async () => {
    vi.stubGlobal("fetch", stubFetch(signedIn));
    renderAt("/no-such-page");
    expect(await screen.findByRole("heading", { name: "Page not found" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Go to overview" })).toHaveAttribute("href", "/");
    expect(screen.getByRole("link", { name: "Alert channels" })).toBeInTheDocument();
  });

  it("names a check that does not exist and leads back to the list", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch({
        ...signedIn,
        "GET /api/v1/checks/gone": () => [404, { detail: "No check gone" }],
      }),
    );
    renderAt("/checks/gone");
    expect(await screen.findByRole("heading", { name: "Check not found" })).toBeInTheDocument();
    expect(screen.getByText(/There is no check gone in this project/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to checks" })).toHaveAttribute("href", "/checks");
  });

  it("uses the same page when the check to edit does not exist", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch({
        ...signedIn,
        "GET /api/v1/checks/gone": () => [404, { detail: "No check gone" }],
      }),
    );
    renderAt("/checks/gone/edit");
    expect(await screen.findByRole("heading", { name: "Check not found" })).toBeInTheDocument();
  });
});

describe("route errors", () => {
  it("offers a retry when the session cannot be read", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch({
        "GET /api/v1/setup": () => [200, { required: false }],
        "GET /api/v1/session": () => [503, { detail: "Service unavailable" }],
      }),
    );
    renderAt("/checks");
    expect(await screen.findByRole("alert")).toHaveTextContent("Service unavailable");
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});
