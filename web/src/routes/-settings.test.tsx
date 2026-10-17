import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Invite, Member, Session } from "@/lib/types";
import { MEMBER_SESSION, SESSION, stubFetch } from "@/test/fixtures";
import { renderAt } from "@/test/render";

afterEach(() => vi.unstubAllGlobals());

type Routes = Parameters<typeof stubFetch>[0];

function signedIn(session: Session, routes: Routes = {}): Routes {
  return {
    "GET /api/v1/setup": () => [200, { required: false }],
    "GET /api/v1/session": () => [200, session],
    "GET /api/v1/checks": () => [200, []],
    ...routes,
  };
}

const MEMBERS: Member[] = [
  {
    accountId: "a1",
    email: "admin@example.com",
    role: "OWNER",
    joinedAt: "2026-01-01T00:00:00Z",
    you: true,
  },
  {
    accountId: "a2",
    email: "lan@example.com",
    role: "MEMBER",
    joinedAt: "2026-01-04T00:00:00Z",
    you: false,
  },
];

function anInvite(overrides: Partial<Invite> = {}): Invite {
  return {
    id: "i1",
    kind: "MEMBER",
    status: "PENDING",
    note: "Minh",
    projectName: "Default",
    createdBy: "admin@example.com",
    createdAt: "2026-01-05T09:00:00Z",
    expiresAt: "2026-01-12T09:00:00Z",
    acceptedBy: null,
    acceptedAt: null,
    url: null,
    ...overrides,
  };
}

describe("members page", () => {
  it("lets an owner invite a member and shows the link once", async () => {
    const created: unknown[] = [];
    vi.stubGlobal(
      "fetch",
      stubFetch(
        signedIn(SESSION, {
          "GET /api/v1/members": () => [200, MEMBERS],
          "GET /api/v1/invites": () => [
            200,
            [anInvite(), anInvite({ id: "i2", status: "ACCEPTED", acceptedBy: "x@example.com" })],
          ],
          "POST /api/v1/invites": (init) => {
            created.push(JSON.parse(String(init?.body)));
            return [201, anInvite({ id: "i3", url: "https://cron.example.com/invite/tok3n" })];
          },
        }),
      ),
    );
    const user = userEvent.setup();
    renderAt("/members");
    expect(await screen.findByRole("heading", { name: "Members" })).toBeInTheDocument();
    const lan = (await screen.findByRole("button", { name: "Actions: lan@example.com" })).closest(
      "tr",
    ) as HTMLElement;
    expect(within(lan).getByText("Member")).toBeInTheDocument();
    const you = screen.getByText("(you)").closest("tr") as HTMLElement;
    expect(within(you).getByText("admin@example.com")).toBeInTheDocument();
    expect(within(you).getByText("Owner")).toBeInTheDocument();
    // Open invites only: the accepted one is a member now.
    expect(await screen.findByText("Minh")).toBeInTheDocument();
    expect(screen.queryByText("by x@example.com")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Invite member" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/join Default as a member/)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText("Note"), "Hoa");
    await user.click(within(dialog).getByRole("button", { name: "Create invite" }));
    expect(
      await within(dialog).findByText("https://cron.example.com/invite/tok3n"),
    ).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Copy link" })).toBeInTheDocument();
    expect(created).toEqual([{ note: "Hoa" }]);
  });

  it("asks before removing a member", async () => {
    let removed = "";
    vi.stubGlobal(
      "fetch",
      stubFetch(
        signedIn(SESSION, {
          "GET /api/v1/members": () => [200, MEMBERS],
          "GET /api/v1/invites": () => [200, []],
          "DELETE /api/v1/members/a2": () => {
            removed = "a2";
            return [204];
          },
        }),
      ),
    );
    const user = userEvent.setup();
    renderAt("/members");
    await user.click(await screen.findByRole("button", { name: "Actions: lan@example.com" }));
    await user.click(await screen.findByRole("menuitem", { name: "Remove" }));
    const confirm = await screen.findByRole("alertdialog");
    expect(within(confirm).getByText(/loses access to this project at once/)).toBeInTheDocument();
    await user.click(within(confirm).getByRole("button", { name: "Remove member" }));
    await waitFor(() => expect(removed).toBe("a2"));
  });

  it("gives a member no invite button and only their own row to act on", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch(
        signedIn(MEMBER_SESSION, {
          "GET /api/v1/members": () => [
            200,
            MEMBERS.map((member) => ({ ...member, you: member.accountId === "a2" })),
          ],
        }),
      ),
    );
    renderAt("/members");
    expect(
      await screen.findByRole("button", { name: "Actions: lan@example.com" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Invite member" })).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Actions: admin@example.com" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("Invite links")).not.toBeInTheDocument();
  });
});

describe("invites page", () => {
  it("lists project invites for the instance admin", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch(
        signedIn(SESSION, {
          "GET /api/v1/admin/invites": () => [
            200,
            [
              anInvite({ kind: "PROJECT", note: "Lan, beta tester", projectName: null }),
              anInvite({
                id: "i2",
                kind: "PROJECT",
                note: null,
                status: "ACCEPTED",
                acceptedBy: "hoa@example.com",
                projectName: "Hoa's backups",
              }),
            ],
          ],
        }),
      ),
    );
    renderAt("/invites");
    expect(await screen.findByText("Lan, beta tester")).toBeInTheDocument();
    expect(screen.getByText("Pending")).toBeInTheDocument();
    expect(screen.getByText("Accepted")).toHaveClass("text-ok");
    expect(screen.getByText("by hoa@example.com · Hoa's backups")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Create invite" })).toBeInTheDocument();
  });

  it("is not offered to other accounts", async () => {
    vi.stubGlobal("fetch", stubFetch(signedIn(MEMBER_SESSION)));
    renderAt("/invites");
    expect(await screen.findByText(/Only the instance admin/)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Invites" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Members" })).toBeInTheDocument();
  });
});

describe("project switcher", () => {
  it("switches to another project and starts at its overview", async () => {
    const switched: unknown[] = [];
    const own: Session = {
      ...MEMBER_SESSION,
      project: { id: "p3", name: "lan's project", role: "OWNER" },
    };
    vi.stubGlobal(
      "fetch",
      stubFetch(
        signedIn(MEMBER_SESSION, {
          "GET /api/v1/channels": () => [200, []],
          "POST /api/v1/session/project": (init) => {
            switched.push(JSON.parse(String(init?.body)));
            return [200, own];
          },
        }),
      ),
    );
    const user = userEvent.setup();
    const { router } = renderAt("/channels");
    await user.click(await screen.findByRole("button", { name: "Switch project: Team jobs" }));
    const menu = await screen.findByRole("menu");
    expect(within(menu).getByText("Owner")).toBeInTheDocument();
    await user.click(within(menu).getByRole("menuitem", { name: /lan's project/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/"));
    expect(switched).toEqual([{ projectId: "p3" }]);
    expect(
      await screen.findByRole("button", { name: "Switch project: lan's project" }),
    ).toBeInTheDocument();
  });

  it("is a plain label with one project", async () => {
    vi.stubGlobal("fetch", stubFetch(signedIn(SESSION)));
    renderAt("/");
    expect(await screen.findByRole("link", { name: /Default/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Switch project/ })).not.toBeInTheDocument();
  });

  it("shows an account without a project how to get back in", async () => {
    vi.stubGlobal("fetch", stubFetch(signedIn({ ...MEMBER_SESSION, project: null, projects: [] })));
    renderAt("/");
    expect(
      await screen.findByRole("heading", { name: "You are not in a project" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
  });
});

describe("settings for members", () => {
  it("shows keys to a member without create or revoke", async () => {
    vi.stubGlobal(
      "fetch",
      stubFetch(
        signedIn(MEMBER_SESSION, {
          "GET /api/v1/keys": () => [
            200,
            [{ id: "k1", name: "ci", prefix: "cb_abcde", createdAt: "2026-01-01T00:00:00Z" }],
          ],
        }),
      ),
    );
    renderAt("/keys");
    expect(await screen.findByText("ci")).toBeInTheDocument();
    expect(screen.getByText(/Only owners of this project/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Create key" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Actions: ci" })).not.toBeInTheDocument();
  });

  it("changes the password after checking the repeat", async () => {
    const changes: unknown[] = [];
    vi.stubGlobal(
      "fetch",
      stubFetch(
        signedIn(MEMBER_SESSION, {
          "POST /api/v1/account/password": (init) => {
            changes.push(JSON.parse(String(init?.body)));
            return [204];
          },
        }),
      ),
    );
    const user = userEvent.setup();
    renderAt("/account");
    expect(await screen.findByText("Member · Team jobs")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Current password"), "old password");
    await user.type(screen.getByLabelText("New password"), "new password 1");
    await user.type(screen.getByLabelText("Repeat new password"), "new password 2");
    await user.click(screen.getByRole("button", { name: "Change password" }));
    expect(await screen.findByText("The passwords do not match.")).toBeInTheDocument();
    expect(changes).toEqual([]);
    await user.clear(screen.getByLabelText("Repeat new password"));
    await user.type(screen.getByLabelText("Repeat new password"), "new password 1");
    await user.click(screen.getByRole("button", { name: "Change password" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Password changed");
    expect(changes).toEqual([{ currentPassword: "old password", newPassword: "new password 1" }]);
  });
});
