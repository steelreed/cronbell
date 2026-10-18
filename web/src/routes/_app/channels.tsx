import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { EllipsisIcon, PencilIcon, PlusIcon, SendIcon, Trash2Icon } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { ChannelIcon } from "@/components/channel-icon";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { FormField } from "@/components/form-field";
import { ErrorNote, ErrorState, LoadingList } from "@/components/page";
import { StatusDot } from "@/components/status-text";
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
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { api } from "@/lib/api";
import {
  CHANNEL_FIELDS,
  CHANNEL_TYPES,
  channelConfig,
  channelTarget,
  fieldLabel,
} from "@/lib/channels";
import { channelsQuery } from "@/lib/queries";
import { t } from "@/lib/strings";
import type { Channel, ChannelType } from "@/lib/types";
import { useNow } from "@/lib/use-now";

export const Route = createFileRoute("/_app/channels")({
  component: ChannelsPage,
});

/** Where alerts go: one row per channel with its last delivery, test, edit and delete. */
function ChannelsPage() {
  const channels = useQuery(channelsQuery);
  const [editing, setEditing] = useState<Channel | "new" | null>(null);
  const now = useNow();
  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <p className="max-w-[65ch] text-muted-foreground text-sm text-pretty">{t.channels.lead}</p>
        <Button onClick={() => setEditing("new")} className="shrink-0 self-start sm:self-auto">
          <PlusIcon aria-hidden />
          {t.channels.add}
        </Button>
      </div>
      <section className="overflow-hidden rounded-xl border bg-card">
        {channels.isPending ? (
          <LoadingList rows={3} />
        ) : channels.isError ? (
          <ErrorState error={channels.error} onRetry={() => channels.refetch()} />
        ) : channels.data.length === 0 ? (
          <p className="px-4 py-10 text-center text-muted-foreground text-sm">{t.channels.empty}</p>
        ) : (
          <ul className="divide-y">
            {channels.data.map((channel) => (
              <ChannelRow
                key={channel.id}
                channel={channel}
                now={now}
                onEdit={() => setEditing(channel)}
              />
            ))}
          </ul>
        )}
      </section>
      <Dialog open={editing !== null} onOpenChange={(open) => !open && setEditing(null)}>
        <DialogContent
          className="sm:max-w-lg"
          onInteractOutside={(event) => event.preventDefault()}
        >
          {editing !== null && (
            <ChannelForm
              channel={editing === "new" ? undefined : editing}
              onDone={() => setEditing(null)}
            />
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}

function DeliveryStatus({ channel, now }: { channel: Channel; now: number }) {
  if (channel.lastError !== null) {
    return (
      <span className="flex min-w-0 items-start gap-2 text-sm">
        <StatusDot tone="problem" className="mt-1.5" />
        <span className="min-w-0">
          <span className="text-problem">{t.channels.failed}</span>{" "}
          <span className="text-muted-foreground">
            <Time iso={channel.lastAttemptAt} now={now} />
          </span>
          <span className="mt-0.5 block break-words font-mono text-muted-foreground text-xs">
            {channel.lastError}
          </span>
        </span>
      </span>
    );
  }
  if (channel.lastDeliveryAt) {
    return (
      <span className="flex items-center gap-2 text-sm">
        <StatusDot tone="ok" />
        <span className="text-ok">{t.channels.delivered}</span>
        <span className="text-muted-foreground">
          <Time iso={channel.lastDeliveryAt} now={now} />
        </span>
      </span>
    );
  }
  return (
    <span className="flex items-center gap-2 text-muted-foreground text-sm">
      <StatusDot tone="muted" />
      {t.channels.noDeliveries}
    </span>
  );
}

function ChannelRow({
  channel,
  now,
  onEdit,
}: {
  channel: Channel;
  now: number;
  onEdit: () => void;
}) {
  const queryClient = useQueryClient();
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
  return (
    <li className="grid gap-3 px-4 py-4 sm:px-5 md:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] md:items-center md:gap-6">
      <div className="flex min-w-0 items-center gap-3">
        <span className="grid size-9 shrink-0 place-items-center rounded-lg border bg-page text-muted-foreground">
          <ChannelIcon type={channel.type} />
        </span>
        <div className="min-w-0">
          <p className="truncate font-medium text-sm" title={channel.name}>
            {channel.name}
            <span className="ml-2 font-normal text-muted-foreground text-xs">
              {t.channels.types[channel.type]}
            </span>
          </p>
          <p className="truncate font-mono text-muted-foreground text-xs">
            {channelTarget(channel.type, channel.config)}
          </p>
        </div>
      </div>
      <div className="min-w-0 space-y-1">
        <DeliveryStatus channel={channel} now={now} />
        {channel.pendingDeliveries > 0 && (
          <p className="text-warn text-xs">{t.channels.pending(channel.pendingDeliveries)}</p>
        )}
        {channel.pickedBy > 0 && (
          <p className="text-muted-foreground text-xs">{t.channels.pickedBy(channel.pickedBy)}</p>
        )}
        {test.isSuccess && (
          <p role="status" className="text-ok text-xs">
            {t.channels.testOk}
          </p>
        )}
        {test.error && <ErrorNote error={test.error} />}
      </div>
      <div className="flex items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          onClick={() => test.mutate()}
          disabled={test.isPending}
          aria-busy={test.isPending || undefined}
        >
          <SendIcon aria-hidden />
          {test.isPending ? t.channels.testing : t.channels.test}
        </Button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="ghost"
              size="icon-sm"
              aria-label={`${t.common.actions}: ${channel.name}`}
            >
              <EllipsisIcon aria-hidden />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-auto min-w-44">
            <DropdownMenuItem onSelect={onEdit}>
              <PencilIcon aria-hidden />
              {t.common.edit}
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem variant="destructive" onSelect={() => setConfirming(true)}>
              <Trash2Icon aria-hidden />
              {t.common.delete}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={t.channels.deleteTitle}
        description={
          <>
            <span className="font-medium text-foreground">{channel.name}</span>{" "}
            {t.channels.deleteRest}
            {channel.pickedBy > 0 && ` ${t.channels.deletePicked(channel.pickedBy)}`}
          </>
        }
        confirmLabel={t.channels.deleteButton}
        pending={remove.isPending}
        error={remove.error}
        onConfirm={() => remove.mutate()}
      />
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
    const first = Object.keys(found)[0];
    if (first) {
      document.getElementById(`${id}-${first}`)?.focus();
      return;
    }
    const name = String(data.get("name") ?? "").trim();
    save.mutate({ type, name: name || undefined, config });
  };
  return (
    <form onSubmit={submit} noValidate className="grid gap-5">
      <DialogHeader>
        <DialogTitle>
          {channel ? t.channels.editTitle(channel.name) : t.channels.addTitle}
        </DialogTitle>
        <DialogDescription>{t.channels.lead}</DialogDescription>
      </DialogHeader>
      {!channel && (
        <div className="space-y-2">
          <span id={`${id}-type`} className="block font-medium text-sm">
            {t.channels.type}
          </span>
          <ToggleGroup
            type="single"
            variant="outline"
            aria-labelledby={`${id}-type`}
            value={type}
            onValueChange={(next) => {
              if (!next) return;
              setType(next as ChannelType);
              setErrors({});
            }}
            className="grid w-full grid-cols-2 sm:grid-cols-3"
          >
            {CHANNEL_TYPES.map((option) => (
              <ToggleGroupItem key={option} value={option} className="gap-2">
                <ChannelIcon type={option} />
                {t.channels.types[option]}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
        </div>
      )}
      <FormField id={`${id}-name`} label={t.channels.name} hint={t.channels.nameHint}>
        <Input
          id={`${id}-name`}
          name="name"
          defaultValue={channel?.name ?? ""}
          autoFocus={Boolean(channel)}
        />
      </FormField>
      {CHANNEL_FIELDS[type].map((field) => {
        const fieldId = `${id}-${field.key}`;
        const masked = channel?.config[field.key];
        return (
          <FormField
            key={`${type}-${field.key}`}
            id={fieldId}
            label={fieldLabel(field)}
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
              aria-describedby={errors[field.key] ? `${fieldId}-error` : `${fieldId}-hint`}
              className={field.inputType === "email" ? undefined : "font-mono"}
            />
          </FormField>
        );
      })}
      {save.error && <ErrorNote error={save.error} />}
      <DialogFooter>
        <Button type="button" variant="ghost" size="lg" onClick={onDone}>
          {t.common.cancel}
        </Button>
        <Button
          type="submit"
          size="lg"
          disabled={save.isPending}
          aria-busy={save.isPending || undefined}
        >
          {save.isPending ? t.common.saving : channel ? t.common.save : t.channels.add}
        </Button>
      </DialogFooter>
    </form>
  );
}
