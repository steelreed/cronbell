import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { EllipsisIcon, KeyRoundIcon, PlusIcon } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { CopyButton } from "@/components/copy-button";
import { FormField } from "@/components/form-field";
import { ErrorNote, ErrorState, LoadingList } from "@/components/page";
import { Time } from "@/components/time";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { api } from "@/lib/api";
import { keysQuery, useIsOwner } from "@/lib/queries";
import { t } from "@/lib/strings";
import type { ApiKey, NewApiKey } from "@/lib/types";
import { useNow } from "@/lib/use-now";

export const Route = createFileRoute("/_app/keys")({
  component: KeysPage,
});

/** Keys for scripts and the starter: name, prefix, age; create shows the key once; revoke. */
function KeysPage() {
  const keys = useQuery(keysQuery);
  const now = useNow();
  const [creating, setCreating] = useState(false);
  const owner = useIsOwner();
  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="max-w-[65ch] text-muted-foreground text-sm text-pretty">
          <p>{t.keys.lead}</p>
          {!owner && <p className="mt-1">{t.keys.ownersOnly}</p>}
        </div>
        {owner && (
          <Button onClick={() => setCreating(true)} className="shrink-0 self-start sm:self-auto">
            <PlusIcon aria-hidden />
            {t.keys.create}
          </Button>
        )}
      </div>
      <section className="overflow-hidden rounded-xl border bg-card">
        {keys.isPending ? (
          <LoadingList rows={2} />
        ) : keys.isError ? (
          <ErrorState error={keys.error} onRetry={() => keys.refetch()} />
        ) : keys.data.length === 0 ? (
          <p className="px-4 py-10 text-center text-muted-foreground text-sm">{t.keys.empty}</p>
        ) : (
          <table className="w-full table-fixed text-sm">
            <thead className="border-b">
              <tr className="text-left text-muted-foreground text-xs">
                <th scope="col" className="h-10 px-4 font-medium sm:pl-5">
                  {t.keys.columns.name}
                </th>
                <th scope="col" className="hidden w-48 px-4 font-medium sm:table-cell">
                  {t.keys.columns.key}
                </th>
                <th scope="col" className="w-28 px-4 font-medium sm:w-36">
                  {t.keys.columns.created}
                </th>
                <th scope="col" className="w-14">
                  <span className="sr-only">{t.common.actions}</span>
                </th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {keys.data.map((key) => (
                <KeyRow key={key.id} apiKey={key} now={now} canRevoke={owner} />
              ))}
            </tbody>
          </table>
        )}
      </section>
      <Dialog open={creating} onOpenChange={setCreating}>
        <DialogContent
          className="sm:max-w-md"
          onInteractOutside={(event) => event.preventDefault()}
        >
          {creating && <CreateKey onDone={() => setCreating(false)} />}
        </DialogContent>
      </Dialog>
    </div>
  );
}

function KeyRow({ apiKey, now, canRevoke }: { apiKey: ApiKey; now: number; canRevoke: boolean }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const revoke = useMutation({
    mutationFn: () => api<void>(`/keys/${apiKey.id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keysQuery.queryKey }),
  });
  return (
    <tr>
      <td className="px-4 py-3 sm:pl-5">
        <span className="block truncate font-medium" title={apiKey.name}>
          {apiKey.name}
        </span>
        <code className="block truncate text-muted-foreground text-xs sm:hidden">
          {apiKey.prefix}…
        </code>
      </td>
      <td className="hidden px-4 py-3 sm:table-cell">
        <code className="text-muted-foreground text-xs">{apiKey.prefix}…</code>
      </td>
      <td className="px-4 py-3 text-muted-foreground">
        <Time iso={apiKey.createdAt} now={now} />
      </td>
      <td className="py-3 pr-3 text-right">
        {canRevoke && (
          <>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label={`${t.common.actions}: ${apiKey.name}`}
                >
                  <EllipsisIcon aria-hidden />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-auto min-w-40">
                <DropdownMenuItem variant="destructive" onSelect={() => setConfirming(true)}>
                  <KeyRoundIcon aria-hidden />
                  {t.keys.revokeButton}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
            <ConfirmDialog
              open={confirming}
              onOpenChange={setConfirming}
              title={t.keys.revokeTitle}
              description={
                <>
                  <span className="font-medium text-foreground">{apiKey.name}</span>{" "}
                  {t.keys.revokeRest}
                </>
              }
              confirmLabel={t.keys.revokeButton}
              pending={revoke.isPending}
              error={revoke.error}
              onConfirm={() => revoke.mutate()}
            />
          </>
        )}
      </td>
    </tr>
  );
}

/** Name the key, then the same dialog shows it once with a copy button. */
function CreateKey({ onDone }: { onDone: () => void }) {
  const id = useId();
  const queryClient = useQueryClient();
  const [created, setCreated] = useState<NewApiKey | null>(null);
  const create = useMutation({
    mutationFn: (name: string) => api<NewApiKey>("/keys", { method: "POST", body: { name } }),
    onSuccess: async (key) => {
      setCreated(key);
      await queryClient.invalidateQueries({ queryKey: keysQuery.queryKey });
    },
  });
  if (created) {
    return (
      <div className="grid gap-5">
        <DialogHeader>
          <DialogTitle>{t.keys.createdTitle}</DialogTitle>
          <DialogDescription>{t.keys.created}</DialogDescription>
        </DialogHeader>
        <code className="block select-all break-all rounded-lg border bg-page px-3 py-2.5 font-mono text-[13px]">
          {created.key}
        </code>
        <DialogFooter>
          <Button variant="ghost" size="lg" onClick={onDone}>
            {t.keys.done}
          </Button>
          <CopyButton value={created.key} label={t.keys.copyKey} variant="default" size="lg" />
        </DialogFooter>
      </div>
    );
  }
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    create.mutate(String(new FormData(event.currentTarget).get("name") ?? ""));
  };
  return (
    <form onSubmit={submit} className="grid gap-5">
      <DialogHeader>
        <DialogTitle>{t.keys.createTitle}</DialogTitle>
        <DialogDescription>{t.keys.lead}</DialogDescription>
      </DialogHeader>
      <FormField id={`${id}-name`} label={t.keys.name} hint={t.keys.nameHint}>
        <Input
          id={`${id}-name`}
          name="name"
          placeholder={t.keys.namePlaceholder}
          maxLength={200}
          autoFocus
          aria-describedby={`${id}-name-hint`}
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
          {t.keys.create}
        </Button>
      </DialogFooter>
    </form>
  );
}
