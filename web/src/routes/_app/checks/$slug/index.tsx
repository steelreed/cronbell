import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { ChevronRightIcon, PauseIcon, PencilIcon, PlayIcon, Trash2Icon } from "lucide-react";
import { useState } from "react";
import { CodeBlock, CopyButton } from "@/components/copy-button";
import { NextDue } from "@/components/next-due";
import { ConfirmBar, ErrorNote, Field, LoadingRows, PageHeader } from "@/components/page";
import { StatusText } from "@/components/status-text";
import { Time } from "@/components/time";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ApiError, api } from "@/lib/api";
import { formatDuration, formatSchedule, formatSeconds } from "@/lib/format";
import { checkQuery, pingsQuery } from "@/lib/queries";
import { crontabSnippet, kubernetesSnippet, shellSnippet } from "@/lib/snippets";
import { t } from "@/lib/strings";
import type { Check, Ping, PingKind } from "@/lib/types";
import { useNow } from "@/lib/use-now";
import { cn } from "@/lib/utils";

export const Route = createFileRoute("/_app/checks/$slug/")({
  component: CheckPage,
});

function CheckPage() {
  const { slug } = Route.useParams();
  const check = useQuery(checkQuery(slug));
  const now = useNow();

  const back = (
    <nav className="mb-2 text-sm">
      <Link to="/" className="text-muted-foreground hover:text-foreground">
        ← {t.check.back}
      </Link>
    </nav>
  );
  if (check.isPending) {
    return (
      <>
        {back}
        <LoadingRows rows={6} />
      </>
    );
  }
  if (check.isError) {
    const missing = check.error instanceof ApiError && check.error.status === 404;
    return (
      <>
        {back}
        {missing ? (
          <p className="text-muted-foreground">{t.check.notFound}</p>
        ) : (
          <ErrorNote error={check.error} onRetry={() => check.refetch()} />
        )}
      </>
    );
  }
  const data = check.data;
  return (
    <>
      {back}
      <CheckHeader check={data} now={now} />
      <dl className="grid grid-cols-2 gap-x-6 gap-y-4 rounded-md border p-4 sm:grid-cols-4">
        <Field label={t.check.schedule}>
          <span className="font-mono text-[13px]">{formatSchedule(data)}</span>
        </Field>
        <Field label={t.check.timeZone}>{data.timeZone}</Field>
        <Field label={t.check.grace}>{formatSeconds(data.graceSeconds)}</Field>
        <Field label={t.check.maxRuntime}>
          {data.maxRuntimeSeconds !== null ? (
            formatSeconds(data.maxRuntimeSeconds)
          ) : (
            <span className="text-muted-foreground">{t.check.sameAsGrace}</span>
          )}
        </Field>
        <Field label={t.check.lastPing}>
          <Time iso={data.lastPingAt} now={now} />
        </Field>
        <Field label={t.check.lastSuccess}>
          <Time iso={data.lastSuccessAt} now={now} />
        </Field>
        <Field label={t.check.nextDue}>
          <NextDue check={data} now={now} />
        </Field>
        <Field label={t.check.created}>
          <Time iso={data.createdAt} now={now} />
        </Field>
      </dl>

      <section className="mt-8">
        <h2 className="mb-2 font-medium text-sm">{t.check.pingUrl}</h2>
        <div className="flex flex-wrap items-center gap-2">
          <code className="min-w-0 flex-1 truncate rounded-md border bg-surface px-3 py-1.5 text-[13px]">
            {data.pingUrl}
          </code>
          <CopyButton value={data.pingUrl} />
        </div>
        <p className="mt-4 mb-2 text-muted-foreground text-sm">{t.check.snippetHint}</p>
        <Tabs defaultValue="crontab">
          <TabsList variant="line">
            <TabsTrigger value="crontab">{t.check.snippets.crontab}</TabsTrigger>
            <TabsTrigger value="shell">{t.check.snippets.shell}</TabsTrigger>
            <TabsTrigger value="kubernetes">{t.check.snippets.kubernetes}</TabsTrigger>
          </TabsList>
          <TabsContent value="crontab">
            <CodeBlock code={crontabSnippet(data)} />
          </TabsContent>
          <TabsContent value="shell">
            <CodeBlock code={shellSnippet(data)} />
          </TabsContent>
          <TabsContent value="kubernetes">
            <CodeBlock code={kubernetesSnippet(data)} />
          </TabsContent>
        </Tabs>
      </section>

      <section className="mt-8">
        <h2 className="mb-2 font-medium text-sm">{t.check.pings}</h2>
        <Pings slug={slug} now={now} />
      </section>
    </>
  );
}

function CheckHeader({ check, now }: { check: Check; now: number }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [confirming, setConfirming] = useState(false);
  const path = `/checks/${encodeURIComponent(check.slug)}`;
  const update = (saved: Check) => {
    queryClient.setQueryData(checkQuery(check.slug).queryKey, saved);
    void queryClient.invalidateQueries({ queryKey: ["checks"], exact: true });
  };
  const toggle = useMutation({
    mutationFn: () =>
      api<Check>(`${path}/${check.status === "PAUSED" ? "resume" : "pause"}`, { method: "POST" }),
    onSuccess: update,
  });
  const remove = useMutation({
    mutationFn: () => api<void>(path, { method: "DELETE" }),
    onSuccess: async () => {
      queryClient.removeQueries({ queryKey: ["checks", check.slug] });
      await queryClient.invalidateQueries({ queryKey: ["checks"], exact: true });
      await navigate({ to: "/" });
    },
  });
  const paused = check.status === "PAUSED";
  return (
    <div className="mb-6 space-y-3">
      <PageHeader
        title={check.name}
        actions={
          <>
            <Button variant="outline" onClick={() => toggle.mutate()} disabled={toggle.isPending}>
              {paused ? <PlayIcon /> : <PauseIcon />}
              {paused ? t.check.resume : t.check.pause}
            </Button>
            <Button variant="outline" asChild>
              <Link to="/checks/$slug/edit" params={{ slug: check.slug }}>
                <PencilIcon />
                {t.common.edit}
              </Link>
            </Button>
            <Button
              variant="outline"
              onClick={() => setConfirming(true)}
              aria-expanded={confirming}
            >
              <Trash2Icon />
              {t.common.delete}
            </Button>
          </>
        }
      >
        <div className="mt-2 flex flex-wrap items-baseline gap-x-4 gap-y-1">
          <StatusText check={check} now={now} />
          {check.name !== check.slug && (
            <span className="font-mono text-muted-foreground text-xs">{check.slug}</span>
          )}
        </div>
      </PageHeader>
      {paused && <p className="text-muted-foreground text-sm">{t.check.pausedNote}</p>}
      {confirming && (
        <ConfirmBar
          message={t.check.deleteConfirm(check.slug)}
          confirmLabel={t.check.deleteButton}
          pending={remove.isPending}
          onConfirm={() => remove.mutate()}
          onCancel={() => setConfirming(false)}
        />
      )}
      {(toggle.error || remove.error) && <ErrorNote error={toggle.error ?? remove.error} />}
    </div>
  );
}

const KIND_TONE: Record<PingKind, string> = {
  START: "text-muted-foreground",
  SUCCESS: "text-ok",
  FAIL: "text-problem",
  LOG: "text-muted-foreground",
};

function Pings({ slug, now }: { slug: string; now: number }) {
  const pings = useQuery(pingsQuery(slug));
  if (pings.isPending) return <LoadingRows rows={3} />;
  if (pings.isError) return <ErrorNote error={pings.error} onRetry={() => pings.refetch()} />;
  if (pings.data.length === 0) {
    return (
      <p className="rounded-md border px-4 py-8 text-center text-muted-foreground text-sm">
        {t.check.noPings}
      </p>
    );
  }
  return (
    <>
      <div className="hidden rounded-md border md:block">
        <Table className="table-fixed">
          <TableHeader>
            <TableRow>
              <TableHead className="w-32 pl-4">{t.check.pingColumns.time}</TableHead>
              <TableHead className="w-20">{t.check.pingColumns.kind}</TableHead>
              <TableHead className="w-14 text-right">{t.check.pingColumns.exit}</TableHead>
              <TableHead className="w-24 text-right">{t.check.pingColumns.duration}</TableHead>
              <TableHead className="hidden w-56 lg:table-cell">
                {t.check.pingColumns.source}
              </TableHead>
              <TableHead className="pr-4">{t.check.pingColumns.body}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {pings.data.map((ping) => (
              <PingRow key={ping.id} ping={ping} now={now} />
            ))}
          </TableBody>
        </Table>
      </div>
      <ul className="divide-y rounded-md border md:hidden">
        {pings.data.map((ping) => (
          <PingItem key={ping.id} ping={ping} now={now} />
        ))}
      </ul>
    </>
  );
}

/** The last non-empty line of a ping's output: usually the result or the error. */
function lastLine(body: string) {
  return body.split("\n").findLast((line) => line.trim()) ?? body;
}

function OutputToggle({
  ping,
  open,
  onToggle,
}: {
  ping: Ping;
  open: boolean;
  onToggle: () => void;
}) {
  if (!ping.body) return null;
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-expanded={open}
      aria-label={open ? t.check.hideOutput : t.check.showOutput}
      className="flex w-full min-w-0 items-center gap-1.5 text-left font-mono text-muted-foreground text-xs hover:text-foreground"
    >
      <ChevronRightIcon
        aria-hidden
        className={cn("size-3.5 shrink-0 transition-transform", open && "rotate-90")}
      />
      <span className="truncate">{lastLine(ping.body)}</span>
    </button>
  );
}

function Output({ body }: { body: string }) {
  return (
    <pre className="max-h-96 overflow-auto whitespace-pre-wrap break-words rounded border bg-surface p-3 font-mono text-xs leading-relaxed">
      {body}
    </pre>
  );
}

function PingRow({ ping, now }: { ping: Ping; now: number }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <TableRow className={cn(open && "border-b-0")}>
        <TableCell className="pl-4">
          <Time iso={ping.receivedAt} now={now} />
        </TableCell>
        <TableCell>
          <span className={cn("font-mono text-xs", KIND_TONE[ping.kind])}>
            {t.check.kinds[ping.kind]}
          </span>
        </TableCell>
        <TableCell
          className={cn(
            "text-right font-mono text-xs tabular-nums",
            ping.kind === "FAIL" && "text-problem",
          )}
        >
          {ping.exitCode ?? ""}
        </TableCell>
        <TableCell className="text-right text-xs tabular-nums">
          {formatDuration(ping.durationMs)}
        </TableCell>
        <TableCell className="hidden truncate text-muted-foreground text-xs lg:table-cell">
          {source(ping)}
        </TableCell>
        <TableCell className="pr-4">
          <OutputToggle ping={ping} open={open} onToggle={() => setOpen(!open)} />
        </TableCell>
      </TableRow>
      {open && ping.body && (
        <TableRow className="hover:bg-transparent">
          <TableCell colSpan={6} className="whitespace-normal px-4 pt-0 pb-3">
            <Output body={ping.body} />
          </TableCell>
        </TableRow>
      )}
    </>
  );
}

/** One ping on a phone: event, exit code, duration and time; output below, on demand. */
function PingItem({ ping, now }: { ping: Ping; now: number }) {
  const [open, setOpen] = useState(false);
  const facts = [
    ping.exitCode !== null && `${t.check.pingColumns.exit.toLowerCase()} ${ping.exitCode}`,
    formatDuration(ping.durationMs),
  ].filter(Boolean);
  return (
    <li className="space-y-1.5 px-4 py-3">
      <div className="flex items-baseline justify-between gap-3">
        <span className="min-w-0 truncate text-xs">
          <span className={cn("font-mono", KIND_TONE[ping.kind])}>{t.check.kinds[ping.kind]}</span>
          {facts.length > 0 && (
            <span
              className={cn(
                "tabular-nums",
                ping.kind === "FAIL" ? "text-problem" : "text-muted-foreground",
              )}
            >
              {" · "}
              {facts.join(" · ")}
            </span>
          )}
        </span>
        <span className="shrink-0 text-muted-foreground text-xs">
          <Time iso={ping.receivedAt} now={now} />
        </span>
      </div>
      {ping.body && <OutputToggle ping={ping} open={open} onToggle={() => setOpen(!open)} />}
      {open && ping.body && <Output body={ping.body} />}
    </li>
  );
}

function source(ping: Ping) {
  return [ping.remoteAddr, ping.userAgent].filter(Boolean).join(" · ");
}
