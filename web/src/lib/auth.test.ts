import { describe, expect, it } from "vitest";
import { SESSION } from "@/test/fixtures";
import { authRedirect, safeRedirect } from "./auth";

describe("authRedirect", () => {
  it("sends everyone to setup until it is done", () => {
    expect(authRedirect("app", true, null)).toBe("/setup");
    expect(authRedirect("signin", true, null)).toBe("/setup");
    expect(authRedirect("setup", true, null)).toBeNull();
  });

  it("sends visitors without a session to sign-in", () => {
    expect(authRedirect("app", false, null)).toBe("/signin");
    expect(authRedirect("signin", false, null)).toBeNull();
    expect(authRedirect("setup", false, null)).toBe("/signin");
  });

  it("lets signed-in visitors into the app and out of the auth pages", () => {
    expect(authRedirect("app", false, SESSION)).toBeNull();
    expect(authRedirect("signin", false, SESSION)).toBe("/");
    expect(authRedirect("setup", false, SESSION)).toBe("/");
  });
});

describe("safeRedirect", () => {
  it("follows only same-site paths", () => {
    expect(safeRedirect("/checks/a?x=1")).toBe("/checks/a?x=1");
    expect(safeRedirect("//evil.example.com")).toBe("/");
    expect(safeRedirect("https://evil.example.com")).toBe("/");
    expect(safeRedirect(undefined)).toBe("/");
  });
});
