import { describe, expect, it } from "vitest";
import { headerFor, navFor } from "./nav";

describe("headerFor", () => {
  it("titles top-level pages and gives deeper pages their parents", () => {
    expect(headerFor("/")).toEqual({ title: "Overview" });
    expect(headerFor("/checks")).toEqual({ title: "Checks" });
    expect(headerFor("/keys")).toEqual({ title: "API keys" });
    expect(headerFor("/checks/new")).toEqual({
      crumbs: [{ label: "Checks", href: "/checks" }, { label: "New check" }],
    });
  });

  it("names the check in the breadcrumb, by its slug until the list is loaded", () => {
    const checks = [{ slug: "nightly-backup", name: "Nightly backup" }];
    expect(headerFor("/checks/nightly-backup", checks)).toEqual({
      crumbs: [{ label: "Checks", href: "/checks" }, { label: "Nightly backup" }],
    });
    expect(headerFor("/checks/nightly-backup/edit", checks)).toEqual({
      crumbs: [
        { label: "Checks", href: "/checks" },
        { label: "Nightly backup", href: "/checks/nightly-backup" },
        { label: "Edit" },
      ],
    });
    expect(headerFor("/checks/nightly-backup")).toEqual({
      crumbs: [{ label: "Checks", href: "/checks" }, { label: "nightly-backup" }],
    });
  });

  it("titles the settings pages", () => {
    expect(headerFor("/members")).toEqual({ title: "Members" });
    expect(headerFor("/account")).toEqual({ title: "Account" });
  });
});

describe("navFor", () => {
  it("offers project invites to the instance admin only", () => {
    const admin = navFor({ account: { id: "a1", email: "a@example.com", instanceAdmin: true } });
    const other = navFor({ account: { id: "a2", email: "b@example.com", instanceAdmin: false } });
    expect(admin.map((item) => item.to)).toContain("/invites");
    expect(other.map((item) => item.to)).not.toContain("/invites");
    expect(other.filter((item) => item.group === "settings").map((item) => item.to)).toEqual([
      "/members",
      "/account",
    ]);
  });
});
