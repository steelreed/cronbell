import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { EllipsisIcon, LogOutIcon, UserMinusIcon, UserPlusIcon } from "lucide-react";
import { useState } from "react";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { InviteDialog } from "@/components/invite-dialog";
import { InvitesTable } from "@/components/invites-table";
import { EmptyLine, ErrorState, LoadingList, Panel } from "@/components/page";
import { Time } from "@/components/time";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { api } from "@/lib/api";
import { invitesQuery, membersQuery, useSession, useSessionChanged } from "@/lib/queries";
import { t } from "@/lib/strings";
import type { Member, Session } from "@/lib/types";
import { useNow } from "@/lib/use-now";

export const Route = createFileRoute("/_app/members")({
  component: MembersPage,
});

/**
 * Members of the current project with their role; owners remove members and make member
 * invite links, anyone can leave (DESIGN.md §6, Roles).
 */
function MembersPage() {
  const session = useSession();
  const owner = session?.project?.role === "OWNER";
  const members = useQuery(membersQuery);
  const now = useNow();
  const [inviting, setInviting] = useState(false);
  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <p className="max-w-[65ch] text-muted-foreground text-sm text-pretty">{t.members.lead}</p>
        {owner && (
          <Button onClick={() => setInviting(true)} className="shrink-0 self-start sm:self-auto">
            <UserPlusIcon aria-hidden />
            {t.members.invite}
          </Button>
        )}
      </div>
      <section className="overflow-hidden rounded-xl border bg-card">
        {members.isPending ? (
          <LoadingList rows={2} />
        ) : members.isError ? (
          <ErrorState error={members.error} onRetry={() => members.refetch()} />
        ) : (
          <table className="w-full table-fixed text-sm">
            <thead className="border-b">
              <tr className="text-left text-muted-foreground text-xs">
                <th scope="col" className="h-10 px-4 font-medium sm:pl-5">
                  {t.members.columns.member}
                </th>
                <th scope="col" className="w-24 px-4 font-medium sm:w-32">
                  {t.members.columns.role}
                </th>
                <th scope="col" className="hidden w-36 px-4 font-medium sm:table-cell">
                  {t.members.columns.joined}
                </th>
                <th scope="col" className="w-14">
                  <span className="sr-only">{t.common.actions}</span>
                </th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {members.data.map((member) => (
                <MemberRow key={member.accountId} member={member} owner={owner} now={now} />
              ))}
            </tbody>
          </table>
        )}
      </section>
      {owner && <MemberInvites now={now} />}
      {session?.project && (
        <InviteDialog
          kind="MEMBER"
          projectName={session.project.name}
          open={inviting}
          onOpenChange={setInviting}
        />
      )}
    </div>
  );
}

function MemberRow({ member, owner, now }: { member: Member; owner: boolean; now: number }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const sessionChanged = useSessionChanged();
  const [confirming, setConfirming] = useState(false);
  const remove = useMutation({
    mutationFn: () => api<void>(`/members/${member.accountId}`, { method: "DELETE" }),
    onSuccess: async () => {
      if (member.you) {
        // Left the project: the session moves to another one, or none (DESIGN.md §6).
        sessionChanged(await api<Session>("/session"));
        await navigate({ to: "/" });
        return;
      }
      setConfirming(false);
      await queryClient.invalidateQueries({ queryKey: membersQuery.queryKey });
    },
  });
  const canAct = member.you || owner;
  return (
    <tr>
      <td className="px-4 py-3 sm:pl-5">
        <span className="flex min-w-0 items-baseline gap-2">
          <span className="truncate font-medium" title={member.email}>
            {member.email}
          </span>
          {member.you && (
            <span className="shrink-0 text-muted-foreground text-xs">({t.members.you})</span>
          )}
        </span>
      </td>
      <td className="px-4 py-3">{t.project.roles[member.role]}</td>
      <td className="hidden px-4 py-3 text-muted-foreground sm:table-cell">
        <Time iso={member.joinedAt} now={now} />
      </td>
      <td className="py-3 pr-3 text-right">
        {canAct && (
          <>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label={`${t.common.actions}: ${member.email}`}
                >
                  <EllipsisIcon aria-hidden />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-auto min-w-44">
                <DropdownMenuItem variant="destructive" onSelect={() => setConfirming(true)}>
                  {member.you ? <LogOutIcon aria-hidden /> : <UserMinusIcon aria-hidden />}
                  {member.you ? t.members.leave : t.members.remove}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
            <ConfirmDialog
              open={confirming}
              onOpenChange={setConfirming}
              title={member.you ? t.members.leaveTitle : t.members.removeTitle}
              description={
                member.you ? (
                  t.members.leaveRest
                ) : (
                  <>
                    <span className="font-medium text-foreground">{member.email}</span>{" "}
                    {t.members.removeRest}
                  </>
                )
              }
              confirmLabel={member.you ? t.members.leave : t.members.removeButton}
              pending={remove.isPending}
              error={remove.error}
              onConfirm={() => remove.mutate()}
            />
          </>
        )}
      </td>
    </tr>
  );
}

/** Open and expired member invite links of the project; accepted ones are members now. */
function MemberInvites({ now }: { now: number }) {
  const invites = useQuery(invitesQuery);
  const open = invites.data?.filter((invite) => invite.status !== "ACCEPTED") ?? [];
  return (
    <Panel title={t.members.invitesTitle} lead={t.members.invitesLead} flush>
      {invites.isPending ? (
        <LoadingList rows={1} />
      ) : invites.isError ? (
        <ErrorState error={invites.error} onRetry={() => invites.refetch()} />
      ) : open.length === 0 ? (
        <EmptyLine>{t.members.invitesEmpty}</EmptyLine>
      ) : (
        <div className="border-t">
          <InvitesTable invites={open} now={now} />
        </div>
      )}
    </Panel>
  );
}
