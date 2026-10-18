import {
  BellRingIcon,
  HashIcon,
  type LucideIcon,
  MailIcon,
  MessagesSquareIcon,
  SendIcon,
  WebhookIcon,
} from "lucide-react";
import type { ChannelType } from "@/lib/types";

const ICONS: Record<ChannelType, LucideIcon> = {
  email: MailIcon,
  telegram: SendIcon,
  slack: HashIcon,
  discord: MessagesSquareIcon,
  ntfy: BellRingIcon,
  webhook: WebhookIcon,
};

export function ChannelIcon({
  type,
  className = "size-4",
}: {
  type: ChannelType;
  className?: string;
}) {
  const Icon = ICONS[type];
  return <Icon aria-hidden className={className} />;
}
