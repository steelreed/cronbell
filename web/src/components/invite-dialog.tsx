import { useMutation, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useId, useState } from "react";
import { CopyButton } from "@/components/copy-button";
import { FormField } from "@/components/form-field";
import { ErrorNote } from "@/components/page";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { api } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { adminInvitesQuery, invitesQuery } from "@/lib/queries";
import { t } from "@/lib/strings";
import type { Invite, InviteKind } from "@/lib/types";

/**
 * Makes an invite link: an optional note, then the same dialog shows the link once with a
 * copy button. A project invite is the instance admin's; a member invite joins the current
 * project.
 */
export function InviteDialog({
  kind,
  projectName,
  open,
  onOpenChange,
}: {
  kind: InviteKind;
  /** The current project, named in a member invite's description. */
  projectName?: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md" onInteractOutside={(event) => event.preventDefault()}>
        {open && (
          <CreateInvite kind={kind} projectName={projectName} onDone={() => onOpenChange(false)} />
        )}
      </DialogContent>
    </Dialog>
  );
}

function CreateInvite({
  kind,
  projectName,
  onDone,
}: {
  kind: InviteKind;
  projectName?: string;
  onDone: () => void;
}) {
  const id = useId();
  const queryClient = useQueryClient();
  const [created, setCreated] = useState<Invite | null>(null);
  const list = kind === "PROJECT" ? adminInvitesQuery : invitesQuery;
  const create = useMutation({
    mutationFn: (note: string) =>
      api<Invite>(kind === "PROJECT" ? "/admin/invites" : "/invites", {
        method: "POST",
        body: { note },
      }),
    onSuccess: async (invite) => {
      setCreated(invite);
      await queryClient.invalidateQueries({ queryKey: list.queryKey });
    },
  });
  if (created?.url) {
    return (
      <div className="grid gap-5">
        <DialogHeader>
          <DialogTitle>{t.invites.createdTitle}</DialogTitle>
          <DialogDescription>{t.invites.created(formatDate(created.expiresAt))}</DialogDescription>
        </DialogHeader>
        <code className="block select-all break-all rounded-lg border bg-page px-3 py-2.5 font-mono text-[13px]">
          {created.url}
        </code>
        <DialogFooter>
          <Button variant="ghost" size="lg" onClick={onDone}>
            {t.invites.done}
          </Button>
          <CopyButton value={created.url} label={t.invites.copyLink} variant="default" size="lg" />
        </DialogFooter>
      </div>
    );
  }
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    create.mutate(String(new FormData(event.currentTarget).get("note") ?? ""));
  };
  return (
    <form onSubmit={submit} className="grid gap-5">
      <DialogHeader>
        <DialogTitle>
          {kind === "PROJECT" ? t.invites.createProjectTitle : t.invites.createMemberTitle}
        </DialogTitle>
        <DialogDescription>
          {kind === "PROJECT" ? t.invites.lead : t.invites.memberLead(projectName ?? "")}
        </DialogDescription>
      </DialogHeader>
      <FormField id={`${id}-note`} label={t.invites.note} hint={t.invites.noteHint}>
        <Input
          id={`${id}-note`}
          name="note"
          placeholder={t.invites.notePlaceholder}
          maxLength={200}
          autoFocus
          aria-describedby={`${id}-note-hint`}
        />
      </FormField>
      {create.error && <ErrorNote error={create.error} />}
      <DialogFooter>
        <Button type="button" variant="ghost" size="lg" onClick={onDone}>
          {t.common.cancel}
        </Button>
        <Button
          type="submit"
          size="lg"
          disabled={create.isPending}
          aria-busy={create.isPending || undefined}
        >
          {t.invites.create}
        </Button>
      </DialogFooter>
    </form>
  );
}
