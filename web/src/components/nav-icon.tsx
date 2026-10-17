import {
  BellRingIcon,
  KeyRoundIcon,
  LayoutDashboardIcon,
  ListChecksIcon,
  MailPlusIcon,
  UserRoundIcon,
  UsersRoundIcon,
} from "lucide-react";
import type { ReactNode } from "react";
import type { NavTo } from "@/lib/nav";

/** One icon per page, shared by the sidebar and the command menu. */
export const NAV_ICONS: Record<NavTo, ReactNode> = {
  "/": <LayoutDashboardIcon aria-hidden />,
  "/checks": <ListChecksIcon aria-hidden />,
  "/channels": <BellRingIcon aria-hidden />,
  "/keys": <KeyRoundIcon aria-hidden />,
  "/members": <UsersRoundIcon aria-hidden />,
  "/invites": <MailPlusIcon aria-hidden />,
  "/account": <UserRoundIcon aria-hidden />,
};
