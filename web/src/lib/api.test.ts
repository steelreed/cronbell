import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, api, readCookie } from "./api";

afterEach(() => vi.unstubAllGlobals());

function capture(status = 200, body: unknown = {}, headers: Record<string, string> = {}) {
  const calls: { url: string; init: RequestInit }[] = [];
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    return new Response(status === 204 ? null : JSON.stringify(body), { status, headers });
  });
  return calls;
}

describe("api", () => {
  it("sends the CSRF cookie back as a header on changes only", async () => {
    // biome-ignore lint/suspicious/noDocumentCookie: jsdom has no Cookie Store API
    document.cookie = "XSRF-TOKEN=abc-123; path=/";
    const calls = capture();
    await api("/checks");
    await api("/checks/x", { method: "PUT", body: { cron: "0 * * * *" } });
    expect(new Headers(calls[0].init.headers).has("X-XSRF-TOKEN")).toBe(false);
    const put = new Headers(calls[1].init.headers);
    expect(put.get("X-XSRF-TOKEN")).toBe("abc-123");
    expect(put.get("Content-Type")).toBe("application/json");
    expect(calls[1].url).toBe("/api/v1/checks/x");
    expect(calls[1].init.credentials).toBe("same-origin");
  });

  it("turns problem details into ApiError with Retry-After", async () => {
    capture(429, { detail: "too many failed sign-ins" }, { "Retry-After": "30" });
    const error = await api("/session", { method: "POST", body: {} }).catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 429,
      message: "too many failed sign-ins",
      retryAfter: 30,
    });
  });

  it("returns undefined for 204", async () => {
    capture(204);
    await expect(api("/session", { method: "DELETE" })).resolves.toBeUndefined();
  });
});

describe("readCookie", () => {
  it("finds a cookie among others", () => {
    expect(readCookie("XSRF-TOKEN", "a=1; XSRF-TOKEN=t%3D1; b=2")).toBe("t=1");
    expect(readCookie("missing", "a=1")).toBeNull();
  });
});
