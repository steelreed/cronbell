import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { type FormEvent, useState } from "react";
import { CopyButton } from "@/components/copy-button";
import { ConfirmBar, ErrorNote, LoadingRows, PageHeader } from "@/components/page";
import { Time } from "@/components/time";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { api } from "@/lib/api";
import { keysQuery } from "@/lib/queries";
import { t } from "@/lib/strings";
import type { ApiKey, NewApiKey } from "@/lib/types";
import { useNow } from "@/lib/use-now";

export const Route = createFileRoute("/_app/keys")({
  component: KeysPage,
});

function KeysPage() {
  const queryClient = useQueryClient();
  const keys = useQuery(keysQuery);
  const now = useNow();
  const [created, setCreated] = useState<NewApiKey | null>(null);
  const create = useMutation({
    mutationFn: (name: string) => api<NewApiKey>("/keys", { method: "POST", body: { name } }),
    onSuccess: async (key) => {
      setCreated(key);
      await queryClient.invalidateQueries({ queryKey: keysQuery.queryKey });
    },
  });
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = event.currentTarget;
    create.mutate(String(new FormData(form).get("name") ?? ""), { onSuccess: () => form.reset() });
  };
  return (
    <>
      <PageHeader title={t.keys.title} lead={t.keys.lead} />
      <form onSubmit={submit} className="mb-6 flex max-w-xl flex-wrap items-end gap-2">
        <div className="min-w-48 flex-1 space-y-1.5">
          <label htmlFor="key-name" className="font-medium text-sm">
            {t.keys.name}
          </label>
          <Input id="key-name" name="name" placeholder={t.keys.namePlaceholder} maxLength={200} />
        </div>
        <Button type="submit" disabled={create.isPending}>
          {t.keys.create}
        </Button>
      </form>
      {create.error && <ErrorNote error={create.error} />}
      {created && (
        <div role="status" className="mb-6 space-y-2 rounded-md border border-warn/50 p-4">
          <p className="text-sm">
            <span className="text-warn">{t.keys.created}</span>
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <code className="min-w-0 flex-1 break-all rounded-md border bg-surface px-3 py-1.5 text-[13px]">
              {created.key}
            </code>
            <CopyButton value={created.key} />
            <Button variant="ghost" size="sm" onClick={() => setCreated(null)}>
              {t.keys.done}
            </Button>
          </div>
        </div>
      )}
      {keys.isPending ? (
        <LoadingRows rows={2} />
      ) : keys.isError ? (
        <ErrorNote error={keys.error} onRetry={() => keys.refetch()} />
      ) : keys.data.length === 0 ? (
        <p className="rounded-md border px-4 py-10 text-center text-muted-foreground text-sm">
          {t.keys.empty}
        </p>
      ) : (
        <ul className="divide-y rounded-md border">
          {keys.data.map((key) => (
            <KeyRow key={key.id} apiKey={key} now={now} />
          ))}
        </ul>
      )}
    </>
  );
}

function KeyRow({ apiKey, now }: { apiKey: ApiKey; now: number }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const revoke = useMutation({
    mutationFn: () => api<void>(`/keys/${apiKey.id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keysQuery.queryKey }),
  });
  return (
    <li className="space-y-3 px-4 py-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate font-medium">{apiKey.name}</p>
          <p className="text-muted-foreground text-xs">
            <code>{apiKey.prefix}…</code> · {t.keys.columns.created.toLowerCase()}{" "}
            <Time iso={apiKey.createdAt} now={now} />
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={() => setConfirming(true)}>
          {t.keys.revoke}
        </Button>
      </div>
      {confirming && (
        <ConfirmBar
          message={t.keys.revokeConfirm(apiKey.name)}
          confirmLabel={t.keys.revoke}
          pending={revoke.isPending}
          onConfirm={() => revoke.mutate()}
          onCancel={() => setConfirming(false)}
        />
      )}
      {revoke.error && <ErrorNote error={revoke.error} />}
    </li>
  );
}
