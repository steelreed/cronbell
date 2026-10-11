import type { Session } from "./types";

export type Area = "app" | "signin" | "setup";

/**
 * Where a visitor must go before seeing a page, or null to stay. Setup comes first, then
 * sign-in; signed-in visitors skip the sign-in and setup pages.
 */
export function authRedirect(
  area: Area,
  setupRequired: boolean,
  session: Session | null,
): "/setup" | "/signin" | "/" | null {
  if (setupRequired) return area === "setup" ? null : "/setup";
  if (area === "setup") return session ? "/" : "/signin";
  if (area === "signin") return session ? "/" : null;
  return session ? null : "/signin";
}

/** Only same-site paths are followed after sign-in. */
export function safeRedirect(target: unknown): string {
  return typeof target === "string" && target.startsWith("/") && !target.startsWith("//")
    ? target
    : "/";
}
