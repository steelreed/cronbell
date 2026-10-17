import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useRouterState } from "@tanstack/react-router";
import { CheckIcon, ChevronsUpDownIcon, LogOutIcon, UserRoundIcon } from "lucide-react";
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
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuBadge,
  SidebarMenuButton,
  SidebarMenuItem,
  useSidebar,
} from "@/components/ui/sidebar";
import { api } from "@/lib/api";
import { type NavItem, navFor } from "@/lib/nav";
import { useSessionChanged } from "@/lib/queries";
import { t } from "@/lib/strings";
import type { ProjectRef, Session } from "@/lib/types";
import { CronbellMark } from "./logo";
import { NAV_ICONS } from "./nav-icon";
import { ThemeIcon, ThemeMenu } from "./theme-menu";

/**
 * The app's left sidebar: the project switcher, the project's pages, the settings, then theme
 * and account. Collapses to an icon rail on desktop and opens as a sheet on phones. The active
 * item is a grey fill with the product's amber on its icon, the only place amber marks state.
 */
export function AppSidebar({ session, problems }: { session: Session; problems: number }) {
  const path = useRouterState({ select: (state) => state.location.pathname });
  const { isMobile, setOpenMobile } = useSidebar();
  const close = () => {
    if (isMobile) setOpenMobile(false);
  };
  const pages = navFor(session);
  const group = (name: NavItem["group"]) =>
    pages
      .filter((item) => item.group === name)
      .map((item) => {
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
                {NAV_ICONS[item.to]}
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
      });
  return (
    <Sidebar collapsible="icon">
      <SidebarHeader className="h-14 justify-center border-b px-2">
        <SidebarMenu>
          <SidebarMenuItem>
            <ProjectSwitcher session={session} onNavigate={close} />
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupContent>
            <SidebarMenu aria-label={t.nav.main}>{group("main")}</SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
        <SidebarGroup>
          <SidebarGroupLabel>{t.nav.settings}</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu aria-label={t.nav.settings}>{group("settings")}</SidebarMenu>
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

/** The product mark in a hairline square, standing for the project. */
function ProjectMark() {
  return (
    <span
      aria-hidden
      className="grid size-8 shrink-0 place-items-center rounded-lg border border-sidebar-border bg-sidebar"
    >
      <CronbellMark size={18} />
    </span>
  );
}

function ProjectLabel({ project }: { project: ProjectRef }) {
  return (
    <span className="grid min-w-0 flex-1 text-left leading-tight">
      <span className="truncate font-semibold text-sm">{project.name}</span>
      <span className="truncate text-muted-foreground text-xs">
        {t.product} · {t.project.roles[project.role]}
      </span>
    </span>
  );
}

/**
 * The current project at the top of the sidebar (shadcn-admin's team switcher). With one
 * project it is a link to the overview; with several it opens a menu to switch, which goes
 * to the new project's overview.
 */
export function ProjectSwitcher({
  session,
  onNavigate,
}: {
  session: Session;
  onNavigate?: () => void;
}) {
  const navigate = useNavigate();
  const sessionChanged = useSessionChanged();
  const current = session.project;
  const switchTo = useMutation({
    mutationFn: (projectId: string) =>
      api<Session>("/session/project", { method: "POST", body: { projectId } }),
    onSuccess: async (next) => {
      sessionChanged(next);
      onNavigate?.();
      await navigate({ to: "/" });
    },
  });
  if (!current) return null;
  if (session.projects.length <= 1) {
    return (
      <SidebarMenuButton asChild size="lg" tooltip={current.name}>
        <Link to="/" onClick={onNavigate}>
          <ProjectMark />
          <ProjectLabel project={current} />
        </Link>
      </SidebarMenuButton>
    );
  }
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <SidebarMenuButton
          size="lg"
          tooltip={current.name}
          aria-label={`${t.project.switch}: ${current.name}`}
          className="data-[state=open]:bg-sidebar-accent"
        >
          <ProjectMark />
          <ProjectLabel project={current} />
          <ChevronsUpDownIcon aria-hidden className="ml-auto text-muted-foreground" />
        </SidebarMenuButton>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        side="bottom"
        align="start"
        className="w-(--radix-dropdown-menu-trigger-width) min-w-60"
      >
        <DropdownMenuLabel className="text-muted-foreground text-xs">
          {t.project.projects}
        </DropdownMenuLabel>
        {session.projects.map((project) => (
          <DropdownMenuItem
            key={project.id}
            disabled={switchTo.isPending}
            onSelect={() => {
              if (project.id !== current.id) switchTo.mutate(project.id);
            }}
          >
            <span className="min-w-0 flex-1 truncate">{project.name}</span>
            <span className="text-muted-foreground text-xs">{t.project.roles[project.role]}</span>
            <CheckIcon
              aria-hidden
              className={project.id === current.id ? "opacity-100" : "opacity-0"}
            />
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** Signs out and forgets everything cached, then goes to the sign-in page. */
export function useSignOut() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  return async () => {
    try {
      await api("/session", { method: "DELETE" });
    } finally {
      queryClient.clear();
      await navigate({ to: "/signin" });
    }
  };
}

function AccountMenu({ session }: { session: Session }) {
  const signOut = useSignOut();
  const email = session.account.email;
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
        {session.project && (
          <DropdownMenuItem asChild>
            <Link to="/account">
              <UserRoundIcon aria-hidden />
              {t.nav.account}
            </Link>
          </DropdownMenuItem>
        )}
        <DropdownMenuItem onSelect={signOut} variant="destructive">
          <LogOutIcon aria-hidden />
          {t.account.signOut}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
