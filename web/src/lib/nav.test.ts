import { describe, expect, it } from "vitest";
import { headerFor } from "./nav";

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
});
