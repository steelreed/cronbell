import { useQuery } from "@tanstack/react-query";
import { Link, useRouterState } from "@tanstack/react-router";
import { ChevronRightIcon, SearchIcon } from "lucide-react";
import { type ReactNode, useCallback, useState } from "react";
import { Separator } from "@/components/ui/separator";
import { SidebarInset, SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { headerFor } from "@/lib/nav";
import { checksQuery } from "@/lib/queries";
import { countByHealth } from "@/lib/status";
import { t } from "@/lib/strings";
import type { Session } from "@/lib/types";
import { useNow } from "@/lib/use-now";
import { AppSidebar } from "./app-sidebar";
import { CommandMenu, useCommandShortcut } from "./command-menu";

/** Sidebar, a header with the page title or breadcrumb and search, and the page. */
export function AppShell({ session, children }: { session: Session; children: ReactNode }) {
  const [searching, setSearching] = useState(false);
  const openSearch = useCallback(() => setSearching(true), []);
  useCommandShortcut(openSearch);
  const checks = useQuery(checksQuery);
  const now = useNow();
  const counts = checks.data ? countByHealth(checks.data, now) : null;
  const problems = counts ? counts.down : 0;
  return (
    <SidebarProvider>
      <AppSidebar session={session} problems={problems} />
      <SidebarInset className="min-w-0 bg-page">
        <AppHeader onSearch={openSearch} />
        <main className="mx-auto w-full min-w-0 max-w-[1600px] flex-1 p-4 sm:p-6">{children}</main>
      </SidebarInset>
      <CommandMenu open={searching} onOpenChange={setSearching} />
    </SidebarProvider>
  );
}

function AppHeader({ onSearch }: { onSearch: () => void }) {
  const path = useRouterState({ select: (state) => state.location.pathname });
  const header = headerFor(path);
  return (
    <header className="sticky top-0 z-20 flex h-14 shrink-0 items-center gap-2 border-b bg-background px-3 sm:px-4">
      <SidebarTrigger aria-label={t.nav.toggleSidebar} className="size-9" />
      <Separator
        orientation="vertical"
        className="mr-1 data-vertical:h-5 data-vertical:self-center"
      />
      <div className="min-w-0 flex-1">
        {"title" in header ? (
          <h1 className="truncate font-semibold text-base">{header.title}</h1>
        ) : (
          <nav aria-label="Breadcrumb">
            <ol className="flex min-w-0 items-center gap-1.5 text-sm">
              {header.crumbs.map((crumb, i) => (
                <li key={crumb.href} className="flex min-w-0 items-center gap-1.5">
                  {i > 0 && (
                    <ChevronRightIcon
                      aria-hidden
                      className="size-4 shrink-0 text-muted-foreground"
                    />
                  )}
                  <Link
                    to={crumb.href}
                    className="inline-flex h-9 max-w-48 items-center truncate text-muted-foreground transition-colors hover:text-foreground"
                  >
                    {crumb.label}
                  </Link>
                </li>
              ))}
            </ol>
          </nav>
        )}
      </div>
      <button
        type="button"
        onClick={onSearch}
        className="flex h-9 shrink-0 items-center gap-2 rounded-lg border border-input bg-card px-3 text-muted-foreground text-sm transition-colors hover:bg-muted hover:text-foreground sm:w-64"
      >
        <SearchIcon aria-hidden className="size-4" />
        <span className="sr-only sm:not-sr-only sm:flex-1 sm:text-left">{t.search.open}…</span>
        <kbd className="hidden rounded border px-1.5 font-mono text-muted-foreground text-xs sm:inline">
          ⌘K
        </kbd>
      </button>
    </header>
  );
}
