import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { InviteOffer, Session } from "@/lib/types";
import { MEMBER_SESSION, stubFetch } from "@/test/fixtures";
import { renderAt } from "@/test/render";

afterEach(() => vi.unstubAllGlobals());

const TOKEN = "Ab-_0123456789abcdefghijklmnopqrstuvwxyzABCD";

const PROJECT_OFFER: InviteOffer = {
  kind: "PROJECT",
  projectName: null,
  invitedBy: "admin@example.com",
  expiresAt: "2026-01-12T10:00:00Z",
};

const NEW_SESSION: Session = {
  account: { id: "a9", email: "lan@example.com", instanceAdmin: false },
  project: { id: "p9", name: "Lan's jobs", role: "OWNER" },
  projects: [{ id: "p9", name: "Lan's jobs", role: "OWNER" }],
  pingBaseUrl: "http://localhost/ping/",
};

/** The app after an accept: the overview of the new, empty project. */
const OVERVIEW = {
  "GET /api/v1/setup": () => [200, { required: false }] as [number, unknown],
  "GET /api/v1/checks": () => [200, []] as [number, unknown],
};

describe("invite page", () => {
  it("creates the account and its own project from a project invite", async () => {
    const accepted: unknown[] = [];
    let signedIn = false;
    vi.stubGlobal(
      "fetch",
      stubFetch({
        ...OVERVIEW,
        [`GET /api/v1/invites/${TOKEN}`]: () => [200, PROJECT_OFFER],
        "GET /api/v1/session": () =>
          signedIn ? [200, NEW_SESSION] : [401, { detail: "not signed in" }],
        [`POST /api/v1/invites/${TOKEN}/accept`]: (init) => {
          accepted.push(JSON.parse(String(init?.body)));
          signedIn = true;
          return [201, NEW_SESSION];
        },
      }),
    );
    const user = userEvent.setup();
    const { router } = renderAt(`/invite/${TOKEN}`);
    expect(
      await screen.findByRole("heading", { name: "Get your own Cronbell project" }),
    ).toBeInTheDocument();
    expect(screen.getByText(/admin@example.com invites you to Cronbell/)).toBeInTheDocument();
    expect(screen.getByText(/works once, until 12 Jan 2026/)).toBeInTheDocument();

    await user.type(screen.getByLabelText("Email"), "lan@example.com");
    expect(screen.getByLabelText("Project name")).toHaveAttribute("placeholder", "lan's project");
    await user.type(screen.getByLabelText("Password"), "a long password");
    await user.type(screen.getByLabelText("Repeat password"), "a long password");
    await user.type(screen.getByLabelText("Project name"), "Lan's jobs");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    await waitFor(() => expect(router.state.location.pathname).toBe("/"));
    expect(accepted).toEqual([
      { email: "lan@example.com", password: "a long password", projectName: "Lan's jobs" },
    ]);
    expect(await screen.findByText("Lan's jobs")).toBeInTheDocument();
  });

  it("asks a signed-in visitor only to confirm a member invite", async () => {
    const accepted: unknown[] = [];
    vi.stubGlobal(
      "fetch",
      stubFetch({
        ...OVERVIEW,
        [`GET /api/v1/invites/${TOKEN}`]: () => [
          200,
          { ...PROJECT_OFFER, kind: "MEMBER", projectName: "Team jobs" },
        ],
        "GET /api/v1/session": () => [200, MEMBER_SESSION],
        [`POST /api/v1/invites/${TOKEN}/accept`]: (init) => {
          accepted.push(JSON.parse(String(init?.body)));
          return [200, MEMBER_SESSION];
        },
      }),
    );
    const user = userEvent.setup();
    const { router } = renderAt(`/invite/${TOKEN}`);
    expect(await screen.findByRole("heading", { name: "Join Team jobs" })).toBeInTheDocument();
    expect(screen.queryByLabelText("Password")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Project name")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Accept as lan@example.com" }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/"));
    expect(accepted).toEqual([{}]);
  });

  it("shows the server's reason when the email has an account", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch({
        [`GET /api/v1/invites/${TOKEN}`]: () => [200, PROJECT_OFFER],
        "GET /api/v1/session": () => [401, { detail: "not signed in" }],
        [`POST /api/v1/invites/${TOKEN}/accept`]: () => [
          409,
          { detail: "an account with this email exists: sign in first, then open the link again" },
        ],
      }),
    );
    const user = userEvent.setup();
    renderAt(`/invite/${TOKEN}`);
    await user.type(await screen.findByLabelText("Email"), "admin@example.com");
    await user.type(screen.getByLabelText("Password"), "a long password");
    await user.type(screen.getByLabelText("Repeat password"), "a long password");
    await user.click(screen.getByRole("button", { name: "Create account" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("sign in first");
    expect(screen.getByRole("link", { name: "Sign in first" })).toHaveAttribute(
      "href",
      `/signin?redirect=%2Finvite%2F${TOKEN}`,
    );
  });

  it("says plainly when a link is not valid", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch({
        "GET /api/v1/session": () => [401, { detail: "not signed in" }],
      }),
    );
    renderAt(`/invite/${TOKEN}`);
    expect(
      await screen.findByRole("heading", { name: "This invite link does not work" }),
    ).toBeInTheDocument();
    expect(screen.getByText(/expired, been used or been withdrawn/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Go to sign in" })).toBeInTheDocument();
  });
});
