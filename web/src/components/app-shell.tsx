import { useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useRouterState } from "@tanstack/react-router";
import { LogOutIcon, MoonIcon, SunIcon, UserIcon } from "lucide-react";
import { type ReactNode, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { api } from "@/lib/api";
import { t } from "@/lib/strings";
import { applyTheme, currentTheme, type Theme } from "@/lib/theme";
import type { Session } from "@/lib/types";
import { cn } from "@/lib/utils";
import { CronbellMark } from "./logo";

const NAV = [
  {
    to: "/",
    label: t.nav.checks,
    match: (path: string) => path === "/" || path.startsWith("/checks"),
  },
  { to: "/channels", label: t.nav.channels, match: (path: string) => path.startsWith("/channels") },
  { to: "/keys", label: t.nav.keys, match: (path: string) => path.startsWith("/keys") },
] as const;

export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(() => currentTheme());
  const next = theme === "dark" ? "light" : "dark";
  return (
    <Button
      variant="ghost"
      size="icon"
      aria-label={next === "light" ? t.theme.toLight : t.theme.toDark}
      onClick={() => {
        applyTheme(next);
        setTheme(next);
      }}
    >
      {theme === "dark" ? <SunIcon /> : <MoonIcon />}
    </Button>
  );
}

export function AppShell({ session, children }: { session: Session; children: ReactNode }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const signOut = async () => {
    try {
      await api("/session", { method: "DELETE" });
    } finally {
      queryClient.clear();
      await navigate({ to: "/signin" });
    }
  };
  return (
    <div className="min-h-dvh">
      <header className="sticky top-0 z-30 border-b bg-background/95 backdrop-blur supports-backdrop-filter:bg-background/85">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-6 px-4 sm:px-6">
          <Link to="/" className="flex items-center gap-2 font-semibold tracking-tight">
            <CronbellMark />
            {t.product}
          </Link>
          <nav className="hidden h-full items-stretch gap-5 md:flex" aria-label="Main">
            <NavItems />
          </nav>
          <div className="ml-auto flex items-center gap-1">
            <ThemeToggle />
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon" aria-label={t.account.menu}>
                  <UserIcon />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="min-w-52">
                <DropdownMenuLabel className="truncate font-normal text-muted-foreground">
                  {session.account.email}
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem onSelect={signOut}>
                  <LogOutIcon />
                  {t.account.signOut}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
        <nav
          className="flex h-10 items-stretch gap-5 overflow-x-auto border-t px-4 md:hidden"
          aria-label="Main"
        >
          <NavItems />
        </nav>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6 sm:py-8">{children}</main>
    </div>
  );
}

/** Active item: foreground text and an amber rule, the product accent. */
function NavItems() {
  const path = useRouterState({ select: (state) => state.location.pathname });
  return NAV.map((item) => {
    const active = item.match(path);
    return (
      <Link
        key={item.to}
        to={item.to}
        aria-current={active ? "page" : undefined}
        className={cn(
          "flex items-center whitespace-nowrap border-transparent border-b-2 text-muted-foreground text-sm transition-colors hover:text-foreground",
          active && "border-cron text-foreground",
        )}
      >
        {item.label}
      </Link>
    );
  });
}
