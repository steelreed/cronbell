import { useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useRouterState } from "@tanstack/react-router";
import {
  BellRingIcon,
  ChevronsUpDownIcon,
  KeyRoundIcon,
  LayoutDashboardIcon,
  ListChecksIcon,
  LogOutIcon,
} from "lucide-react";
import type { ReactNode } from "react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuBadge,
  SidebarMenuButton,
  SidebarMenuItem,
  useSidebar,
} from "@/components/ui/sidebar";
import { api } from "@/lib/api";
import { NAV, type NavTo } from "@/lib/nav";
import { t } from "@/lib/strings";
import type { Session } from "@/lib/types";
import { CronbellMark } from "./logo";
import { ThemeIcon, ThemeMenu } from "./theme-menu";

const ICONS: Record<NavTo, ReactNode> = {
  "/": <LayoutDashboardIcon aria-hidden />,
  "/checks": <ListChecksIcon aria-hidden />,
  "/channels": <BellRingIcon aria-hidden />,
  "/keys": <KeyRoundIcon aria-hidden />,
};

/**
 * The app's left sidebar: product mark, the four sections, then theme and account. Collapses
 * to an icon rail on desktop and opens as a sheet on phones. The active item is a grey fill
 * with the product's amber on its icon, the only place amber marks state.
 */
export function AppSidebar({ session, problems }: { session: Session; problems: number }) {
  const path = useRouterState({ select: (state) => state.location.pathname });
  const { isMobile, setOpenMobile } = useSidebar();
  const close = () => {
    if (isMobile) setOpenMobile(false);
  };
  return (
    <Sidebar collapsible="icon">
      <SidebarHeader className="h-14 justify-center border-b px-3 group-data-[collapsible=icon]:px-2">
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton asChild tooltip={t.product} className="font-semibold">
              <Link to="/" onClick={close}>
                <CronbellMark size={18} />
                <span className="tracking-tight">{t.product}</span>
              </Link>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupContent>
            <SidebarMenu aria-label={t.nav.main}>
              {NAV.map((item) => {
                const active = item.match(path);
                return (
                  <SidebarMenuItem key={item.to}>
                    <SidebarMenuButton
                      asChild
                      isActive={active}
                      tooltip={item.label}
                      className="text-sidebar-foreground/75 data-[active=true]:text-sidebar-foreground data-[active=true]:[&>svg]:text-cron"
                    >
                      <Link to={item.to} onClick={close} aria-current={active ? "page" : undefined}>
                        {ICONS[item.to]}
                        <span>{item.label}</span>
                      </Link>
                    </SidebarMenuButton>
                    {item.to === "/checks" && problems > 0 && (
                      <SidebarMenuBadge className="text-problem tabular-nums">
                        {problems > 99 ? "99+" : problems}
                      </SidebarMenuBadge>
                    )}
                  </SidebarMenuItem>
                );
              })}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>
      <SidebarFooter>
        <SidebarMenu>
          <SidebarMenuItem>
            <ThemeMenu
              side="top"
              align="start"
              trigger={(theme) => (
                <SidebarMenuButton tooltip={t.theme.label} className="text-sidebar-foreground/75">
                  <ThemeIcon theme={theme} />
                  <span>
                    {t.theme.label}: {t.theme[theme]}
                  </span>
                </SidebarMenuButton>
              )}
            />
          </SidebarMenuItem>
          <SidebarMenuItem>
            <AccountMenu session={session} />
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarFooter>
    </Sidebar>
  );
}

function AccountMenu({ session }: { session: Session }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const email = session.account.email;
  const signOut = async () => {
    try {
      await api("/session", { method: "DELETE" });
    } finally {
      queryClient.clear();
      await navigate({ to: "/signin" });
    }
  };
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <SidebarMenuButton size="lg" tooltip={t.account.menu} aria-label={t.account.menu}>
          <span
            aria-hidden
            className="grid size-8 shrink-0 place-items-center rounded-full bg-sidebar-accent font-semibold text-sm uppercase ring-1 ring-sidebar-border"
          >
            {email.charAt(0)}
          </span>
          <span className="grid min-w-0 flex-1 text-left leading-tight">
            <span className="truncate font-medium text-sm">{email.split("@")[0]}</span>
            <span className="truncate text-muted-foreground text-xs">{email}</span>
          </span>
          <ChevronsUpDownIcon aria-hidden className="ml-auto text-muted-foreground" />
        </SidebarMenuButton>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        side="top"
        align="start"
        className="w-(--radix-dropdown-menu-trigger-width) min-w-56"
      >
        <DropdownMenuLabel className="font-normal">
          <span className="block text-muted-foreground text-xs">{t.account.signedInAs}</span>
          <span className="block truncate text-sm">{email}</span>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={signOut} variant="destructive">
          <LogOutIcon aria-hidden />
          {t.account.signOut}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
