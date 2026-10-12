import { describe, expect, it } from "vitest";
import { headerFor, navFor } from "./nav";

describe("headerFor", () => {
  it("titles top-level pages and gives deeper pages their parents", () => {
    expect(headerFor("/")).toEqual({ title: "Overview" });
    expect(headerFor("/checks")).toEqual({ title: "Checks" });
    expect(headerFor("/keys")).toEqual({ title: "API keys" });
    expect(headerFor("/checks/nightly-backup")).toEqual({
      crumbs: [{ label: "Checks", href: "/checks" }],
    });
    expect(headerFor("/checks/nightly-backup/edit")).toEqual({
      crumbs: [
        { label: "Checks", href: "/checks" },
        { label: "nightly-backup", href: "/checks/nightly-backup" },
      ],
    });
    expect(headerFor("/checks/new")).toEqual({ crumbs: [{ label: "Checks", href: "/checks" }] });
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
