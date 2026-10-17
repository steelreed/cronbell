import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { MonitorIcon, MoonIcon, PlusIcon, SunIcon } from "lucide-react";
import { useEffect } from "react";
import {
  Command,
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import { NAV, type NavItem } from "@/lib/nav";
import { checksQuery } from "@/lib/queries";
import { statusView } from "@/lib/status";
import { t } from "@/lib/strings";
import { type Theme, useTheme } from "@/lib/theme";
import { NAV_ICONS } from "./nav-icon";
import { StatusDot } from "./status-text";

const NEXT_THEME: Record<Theme, Theme> = { dark: "light", light: "system", system: "dark" };

/** Opens on ⌘K, Ctrl+K or "/" outside a text field. */
export function useCommandShortcut(onOpen: () => void) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      const typing =
        target?.isContentEditable ||
        ["INPUT", "TEXTAREA", "SELECT"].includes(target?.tagName ?? "");
      if (
        (event.key === "k" && (event.metaKey || event.ctrlKey)) ||
        (event.key === "/" && !typing)
      ) {
        event.preventDefault();
        onOpen();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onOpen]);
}

/**
 * The command menu: jump to a check by name or slug, go to a page, create a check, switch
 * theme. cmdk filters by the item's value plus keywords (the slug), not by group names.
 */
export function CommandMenu({
  open,
  onOpenChange,
  pages = NAV.filter((item) => !item.adminOnly),
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** The pages this account can open. */
  pages?: NavItem[];
}) {
  const navigate = useNavigate();
  const checks = useQuery({ ...checksQuery, enabled: open });
  const [theme, setTheme] = useTheme();
  const run = (action: () => void) => {
    onOpenChange(false);
    action();
  };
  const now = Date.now();
  return (
    <CommandDialog
      open={open}
      onOpenChange={onOpenChange}
      title={t.search.open}
      description={t.search.placeholder}
      className="top-4 sm:top-[15vh] sm:max-w-xl"
    >
      <Command loop>
        <CommandInput placeholder={t.search.placeholder} />
        <CommandList className="max-h-[min(22rem,60vh)]">
          <CommandEmpty>{t.search.empty}</CommandEmpty>
          {checks.data && checks.data.length > 0 && (
            <CommandGroup heading={t.search.checks}>
              {checks.data.map((check) => {
                const view = statusView(check, now);
                return (
                  <CommandItem
                    key={check.slug}
                    value={`check ${check.slug}`}
                    keywords={[check.name, check.slug]}
                    onSelect={() =>
                      run(() => navigate({ to: "/checks/$slug", params: { slug: check.slug } }))
                    }
                  >
                    <StatusDot tone={view.tone} />
                    <span className="min-w-0 flex-1 truncate">{check.name}</span>
                    {check.name !== check.slug && (
                      <span className="max-w-[40%] truncate font-mono text-muted-foreground text-xs">
                        {check.slug}
                      </span>
                    )}
                  </CommandItem>
                );
              })}
            </CommandGroup>
          )}
          <CommandGroup heading={t.search.pages}>
            {pages.map((item) => (
              <CommandItem
                key={item.to}
                value={`page ${item.label}`}
                keywords={[item.label]}
                onSelect={() => run(() => navigate({ to: item.to }))}
              >
                {NAV_ICONS[item.to]}
                {item.label}
              </CommandItem>
            ))}
          </CommandGroup>
          <CommandGroup heading={t.search.actions}>
            <CommandItem
              value="action new check"
              keywords={[t.search.newCheck, "create", "add"]}
              onSelect={() => run(() => navigate({ to: "/checks/new" }))}
            >
              <PlusIcon aria-hidden />
              {t.search.newCheck}
            </CommandItem>
            <CommandItem
              value="action theme"
              keywords={[t.search.toggleTheme, "dark", "light", "theme"]}
              onSelect={() => run(() => setTheme(NEXT_THEME[theme]))}
            >
              {theme === "dark" ? (
                <SunIcon aria-hidden />
              ) : theme === "light" ? (
                <MonitorIcon aria-hidden />
              ) : (
                <MoonIcon aria-hidden />
              )}
              {t.search.toggleTheme}: {t.theme[NEXT_THEME[theme]]}
            </CommandItem>
          </CommandGroup>
        </CommandList>
      </Command>
    </CommandDialog>
  );
}
