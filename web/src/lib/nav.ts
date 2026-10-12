import { t } from "./strings";

export type NavTo = "/" | "/checks" | "/channels" | "/keys";

export interface NavItem {
  to: NavTo;
  label: string;
  match: (path: string) => boolean;
}

export const NAV: NavItem[] = [
  { to: "/", label: t.nav.overview, match: (path) => path === "/" },
  { to: "/checks", label: t.nav.checks, match: (path) => path.startsWith("/checks") },
  { to: "/channels", label: t.nav.channels, match: (path) => path.startsWith("/channels") },
  { to: "/keys", label: t.nav.keys, match: (path) => path.startsWith("/keys") },
];

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
