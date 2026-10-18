import { t } from "./strings";
import type { Check, Session } from "./types";

export type NavTo =
  | "/"
  | "/checks"
  | "/channels"
  | "/keys"
  | "/members"
  | "/account"
  | "/admin/invites";

export interface NavItem {
  to: NavTo;
  label: string;
  /** Main pages of the project, settings of the project and the account, or of the instance. */
  group: "main" | "settings" | "admin";
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
  { to: "/account", label: t.nav.account, group: "settings", match: starts("/account") },
  {
    to: "/admin/invites",
    label: t.nav.instanceInvites,
    group: "admin",
    adminOnly: true,
    match: starts("/admin/invites"),
  },
];

/** The pages this account sees: the instance admin also gets project invites. */
export function navFor(session: Pick<Session, "account">): NavItem[] {
  return NAV.filter((item) => !item.adminOnly || session.account.instanceAdmin);
}

export interface Crumb {
  label: string;
  /** A route path; the crumb is a link to it. The current page has none. */
  href?: string;
}

/**
 * What the header bar shows for a path. Top-level pages have no page header: the bar's title
 * is their <h1>. Deeper pages (a check, its form) show the way there as a breadcrumb, ending
 * with the page itself; a check is named as in the list, by its slug until the list is loaded.
 */
export function headerFor(
  path: string,
  checks: Pick<Check, "slug" | "name">[] = [],
): { title: string } | { crumbs: Crumb[] } {
  const top = NAV.find((item) => item.to === path || `${item.to}/` === path);
  if (top) return { title: top.label };
  const parent: Crumb = { label: t.nav.checks, href: "/checks" };
  if (/^\/checks\/new\/?$/.test(path)) return { crumbs: [parent, { label: t.form.newTitle }] };
  const check = /^\/checks\/([^/]+)(\/edit)?\/?$/.exec(path);
  if (check) {
    const slug = decodeURIComponent(check[1]);
    const label = checks.find((item) => item.slug === slug)?.name ?? slug;
    return {
      crumbs: check[2]
        ? [parent, { label, href: `/checks/${check[1]}` }, { label: t.common.edit }]
        : [parent, { label }],
    };
  }
  return { title: "" };
}
