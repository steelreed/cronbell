import { t } from "./strings";
import type { Session } from "./types";

export type NavTo = "/" | "/checks" | "/channels" | "/keys" | "/members" | "/invites" | "/account";

export interface NavItem {
  to: NavTo;
  label: string;
  /** Main pages of the project, or settings of the project and the account. */
  group: "main" | "settings";
  /** Shown to the instance admin only. */
  adminOnly?: boolean;
  match: (path: string) => boolean;
}

const starts = (prefix: string) => (path: string) => path.startsWith(prefix);

export const NAV: NavItem[] = [
  { to: "/", label: t.nav.overview, group: "main", match: (path) => path === "/" },
  { to: "/checks", label: t.nav.checks, group: "main", match: starts("/checks") },
  { to: "/channels", label: t.nav.channels, group: "main", match: starts("/channels") },
  { to: "/keys", label: t.nav.keys, group: "main", match: starts("/keys") },
  { to: "/members", label: t.nav.members, group: "settings", match: starts("/members") },
  {
    to: "/invites",
    label: t.nav.invites,
    group: "settings",
    adminOnly: true,
    match: starts("/invites"),
  },
  { to: "/account", label: t.nav.account, group: "settings", match: starts("/account") },
];

/** The pages this account sees: the instance admin also gets project invites. */
export function navFor(session: Pick<Session, "account">): NavItem[] {
  return NAV.filter((item) => !item.adminOnly || session.account.instanceAdmin);
}

export interface Crumb {
  label: string;
  /** A route path; the crumb is a link to it. */
  href: string;
}

/**
 * What the header bar shows for a path. Top-level pages have no page header: the bar's title
 * is their <h1>. Deeper pages (a check, its form) have their own <h1>, so the bar shows only
 * the parents as a breadcrumb.
 */
export function headerFor(path: string): { title: string } | { crumbs: Crumb[] } {
  const top = NAV.find((item) => item.to === path || `${item.to}/` === path);
  if (top) return { title: top.label };
  const check = /^\/checks\/([^/]+)(\/edit)?\/?$/.exec(path);
  if (check && check[1] !== "new") {
    const crumbs: Crumb[] = [{ label: t.nav.checks, href: "/checks" }];
    if (check[2]) {
      const slug = decodeURIComponent(check[1]);
      crumbs.push({ label: slug, href: `/checks/${check[1]}` });
    }
    return { crumbs };
  }
  if (path.startsWith("/checks/new")) return { crumbs: [{ label: t.nav.checks, href: "/checks" }] };
  return { title: "" };
}
