import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { PencilIcon, PlusIcon, SendIcon, Trash2Icon } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { FormField } from "@/components/form-field";
import { ConfirmBar, ErrorNote, LoadingRows, PageHeader } from "@/components/page";
import { Time } from "@/components/time";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { api } from "@/lib/api";
import { CHANNEL_FIELDS, CHANNEL_TYPES, channelConfig, channelTarget } from "@/lib/channels";
import { channelsQuery } from "@/lib/queries";
import { t } from "@/lib/strings";
import type { Channel, ChannelType } from "@/lib/types";
import { useNow } from "@/lib/use-now";

export const Route = createFileRoute("/_app/channels")({
  component: ChannelsPage,
});

function ChannelsPage() {
  const channels = useQuery(channelsQuery);
  const [adding, setAdding] = useState(false);
  const now = useNow();
  return (
    <>
      <PageHeader
        title={t.channels.title}
        lead={t.channels.lead}
        actions={
          !adding && (
            <Button onClick={() => setAdding(true)}>
              <PlusIcon />
              {t.channels.add}
            </Button>
          )
        }
      />
      {adding && (
        <div className="mb-6 rounded-md border p-4 sm:p-5">
          <ChannelForm onDone={() => setAdding(false)} />
        </div>
      )}
      {channels.isPending ? (
        <LoadingRows rows={3} />
      ) : channels.isError ? (
        <ErrorNote error={channels.error} onRetry={() => channels.refetch()} />
      ) : channels.data.length === 0 ? (
        !adding && (
          <p className="rounded-md border px-4 py-10 text-center text-muted-foreground text-sm">
            {t.channels.empty}
          </p>
        )
      ) : (
        <ul className="divide-y rounded-md border">
          {channels.data.map((channel) => (
            <ChannelRow key={channel.id} channel={channel} now={now} />
          ))}
        </ul>
      )}
    </>
  );
}

function ChannelRow({ channel, now }: { channel: Channel; now: number }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const test = useMutation({
    mutationFn: () =>
      api<{ delivered: boolean }>(`/channels/${channel.id}/test`, { method: "POST" }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: channelsQuery.queryKey }),
  });
  const remove = useMutation({
    mutationFn: () => api<void>(`/channels/${channel.id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: channelsQuery.queryKey }),
  });
  if (editing) {
    return (
      <li className="p-4 sm:p-5">
        <ChannelForm channel={channel} onDone={() => setEditing(false)} />
      </li>
    );
  }
  const failed = channel.lastError !== null;
  return (
    <li className="space-y-3 px-4 py-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 space-y-0.5">
          <div className="flex items-baseline gap-2">
            <span className="font-medium">{channel.name}</span>
            <span className="text-muted-foreground text-xs">{t.channels.types[channel.type]}</span>
          </div>
          <p className="truncate font-mono text-muted-foreground text-xs">
            {channelTarget(channel.type, channel.config)}
          </p>
          <p className="text-sm">
            {failed ? (
              <span className="text-problem">
                <span className="mr-1.5 inline-block size-2 rounded-full bg-problem" aria-hidden />
                {t.channels.failed} <Time iso={channel.lastAttemptAt} now={now} />:{" "}
                {channel.lastError}
              </span>
            ) : channel.lastDeliveryAt ? (
              <span className="text-ok">
                <span className="mr-1.5 inline-block size-2 rounded-full bg-ok" aria-hidden />
                {t.channels.delivered("")}
                <Time iso={channel.lastDeliveryAt} now={now} />
              </span>
            ) : (
              <span className="text-muted-foreground">{t.channels.noDeliveries}</span>
            )}
            {channel.pendingDeliveries > 0 && (
              <span className="ml-2 text-warn">
                {t.channels.pending(channel.pendingDeliveries)}
              </span>
            )}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => test.mutate()}
            disabled={test.isPending}
          >
            <SendIcon />
            {test.isPending ? t.channels.testing : t.channels.test}
          </Button>
          <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
            <PencilIcon />
            {t.common.edit}
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setConfirming(true)}
            aria-label={`${t.common.delete} ${channel.name}`}
          >
            <Trash2Icon />
          </Button>
        </div>
      </div>
      {test.isSuccess && (
        <p role="status" className="text-ok text-sm">
          {t.channels.testOk}
        </p>
      )}
      {test.error && <ErrorNote error={test.error} />}
      {confirming && (
        <ConfirmBar
          message={t.channels.deleteConfirm(channel.name)}
          confirmLabel={t.common.delete}
          pending={remove.isPending}
          onConfirm={() => remove.mutate()}
          onCancel={() => setConfirming(false)}
        />
      )}
      {remove.error && <ErrorNote error={remove.error} />}
    </li>
  );
}

function ChannelForm({ channel, onDone }: { channel?: Channel; onDone: () => void }) {
  const id = useId();
  const queryClient = useQueryClient();
  const [type, setType] = useState<ChannelType>(channel?.type ?? "telegram");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: (body: { type: ChannelType; name?: string; config: Record<string, string> }) =>
      channel
        ? api<Channel>(`/channels/${channel.id}`, { method: "PUT", body })
        : api<Channel>("/channels", { method: "POST", body }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: channelsQuery.queryKey });
      onDone();
    },
  });
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const values: Record<string, string> = {};
    for (const field of CHANNEL_FIELDS[type]) values[field.key] = String(data.get(field.key) ?? "");
    const { config, errors: found } = channelConfig(type, values, Boolean(channel));
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    const name = String(data.get("name") ?? "").trim();
    save.mutate({ type, name: name || undefined, config });
  };
  return (
    <form onSubmit={submit} noValidate className="max-w-xl space-y-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <FormField id={`${id}-type`} label={t.channels.type}>
          <select
            id={`${id}-type`}
            value={type}
            disabled={Boolean(channel)}
            onChange={(event) => {
              setType(event.target.value as ChannelType);
              setErrors({});
            }}
            className="h-8 w-full rounded-lg border border-input bg-transparent px-2 text-sm disabled:opacity-60 dark:bg-input/30"
          >
            {CHANNEL_TYPES.map((option) => (
              <option key={option} value={option}>
                {t.channels.types[option]}
              </option>
            ))}
          </select>
        </FormField>
        <FormField id={`${id}-name`} label={t.channels.name} hint={t.channels.nameHint}>
          <Input id={`${id}-name`} name="name" defaultValue={channel?.name ?? ""} />
        </FormField>
      </div>
      {CHANNEL_FIELDS[type].map((field) => {
        const fieldId = `${id}-${field.key}`;
        const masked = channel?.config[field.key];
        return (
          <FormField
            key={`${type}-${field.key}`}
            id={fieldId}
            label={t.channels.fields[field.key as keyof typeof t.channels.fields]}
            hint={
              channel && field.secret
                ? `${t.channels.secretKept}${masked ? ` (${masked})` : ""}`
                : field.hint
            }
            error={errors[field.key]}
          >
            <Input
              id={fieldId}
              name={field.key}
              type={field.inputType}
              defaultValue={field.secret ? "" : (masked ?? "")}
              placeholder={channel && field.secret ? masked : field.placeholder}
              autoComplete="off"
              spellCheck={false}
              aria-invalid={errors[field.key] ? true : undefined}
              className={field.inputType === "email" ? undefined : "font-mono"}
            />
          </FormField>
        );
      })}
      {save.error && <ErrorNote error={save.error} />}
      <div className="flex gap-2">
        <Button type="submit" disabled={save.isPending}>
          {save.isPending ? t.common.saving : channel ? t.common.save : t.channels.add}
        </Button>
        <Button type="button" variant="ghost" onClick={onDone}>
          {t.common.cancel}
        </Button>
      </div>
    </form>
  );
}
