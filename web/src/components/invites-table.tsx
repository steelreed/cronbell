import { useMutation, useQueryClient } from "@tanstack/react-query";
import { EllipsisIcon, Trash2Icon } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { StatusDot } from "@/components/status-text";
import { Time } from "@/components/time";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { api } from "@/lib/api";
import { adminInvitesQuery, invitesQuery } from "@/lib/queries";
import type { Tone } from "@/lib/status";
import { t } from "@/lib/strings";
import type { Invite } from "@/lib/types";
import { cn } from "@/lib/utils";

const TONE: Record<Invite["status"], Tone> = {
  PENDING: "muted",
  ACCEPTED: "ok",
  EXPIRED: "muted",
};

/** Invites with their status, who made or accepted them, when they expire, and delete. */
export function InvitesTable({ invites, now }: { invites: Invite[]; now: number }) {
  return (
    <table className="w-full table-fixed text-sm">
      <thead className="border-b">
        <tr className="text-left text-muted-foreground text-xs">
          <th scope="col" className="h-10 px-4 font-medium sm:pl-5">
            {t.invites.columns.invite}
          </th>
          <th scope="col" className="w-32 px-4 font-medium sm:w-56 lg:w-80">
            {t.invites.columns.status}
          </th>
          <th scope="col" className="hidden w-36 px-4 font-medium md:table-cell">
            {t.invites.columns.created}
          </th>
          <th scope="col" className="hidden w-36 px-4 font-medium sm:table-cell">
            {t.invites.columns.expires}
          </th>
          <th scope="col" className="w-14">
            <span className="sr-only">{t.common.actions}</span>
          </th>
        </tr>
      </thead>
      <tbody className="divide-y">
        {invites.map((invite) => (
          <InviteRow key={invite.id} invite={invite} now={now} />
        ))}
      </tbody>
    </table>
  );
}

function InviteRow({ invite, now }: { invite: Invite; now: number }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const project = invite.kind === "PROJECT";
  const remove = useMutation({
    mutationFn: () =>
      api<void>(`${project ? "/admin/invites" : "/invites"}/${invite.id}`, { method: "DELETE" }),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: (project ? adminInvitesQuery : invitesQuery).queryKey,
      }),
  });
  const title = invite.note ?? t.invites.noNote;
  return (
    <tr className={cn(invite.status === "EXPIRED" && "text-muted-foreground")}>
      <td className="px-4 py-3 sm:pl-5">
        <span className="block truncate font-medium" title={title}>
          {title}
        </span>
        <span className="block truncate text-muted-foreground text-xs">
          {t.invites.by(invite.createdBy)}
        </span>
      </td>
      <td className="px-4 py-3">
        <span className="flex items-center gap-1.5">
          <StatusDot tone={TONE[invite.status]} />
          <span className={invite.status === "ACCEPTED" ? "text-ok" : undefined}>
            {t.invites.status[invite.status]}
          </span>
        </span>
        {invite.status === "ACCEPTED" && invite.acceptedBy && (
          <span className="block truncate text-muted-foreground text-xs" title={invite.acceptedBy}>
            {t.invites.by(invite.acceptedBy)}
            {project && invite.projectName ? ` · ${invite.projectName}` : ""}
          </span>
        )}
      </td>
      <td className="hidden px-4 py-3 text-muted-foreground md:table-cell">
        <Time iso={invite.createdAt} now={now} />
      </td>
      <td className="hidden px-4 py-3 text-muted-foreground sm:table-cell">
        {invite.status === "ACCEPTED" ? "—" : <Time iso={invite.expiresAt} now={now} />}
      </td>
      <td className="py-3 pr-3 text-right">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon-sm" aria-label={`${t.common.actions}: ${title}`}>
              <EllipsisIcon aria-hidden />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-auto min-w-40">
            <DropdownMenuItem variant="destructive" onSelect={() => setConfirming(true)}>
              <Trash2Icon aria-hidden />
              {t.invites.delete}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
        <ConfirmDialog
          open={confirming}
          onOpenChange={setConfirming}
          title={t.invites.deleteTitle}
          description={
            invite.status === "ACCEPTED" ? t.invites.deleteDone : t.invites.deletePending
          }
          confirmLabel={t.invites.deleteButton}
          pending={remove.isPending}
          error={remove.error}
          onConfirm={() => remove.mutate()}
        />
      </td>
    </tr>
  );
}
