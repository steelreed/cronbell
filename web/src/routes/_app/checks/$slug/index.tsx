import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { PauseIcon, PencilIcon, PlayIcon, Trash2Icon } from "lucide-react";
import { useState } from "react";
import { CodeBlock, CopyButton } from "@/components/copy-button";
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
          {data.status === "PAUSED" ? "—" : <Time iso={data.nextDueAt} now={now} />}
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
    <div className="overflow-x-auto rounded-md border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="pl-4">{t.check.pingColumns.time}</TableHead>
            <TableHead>{t.check.pingColumns.kind}</TableHead>
            <TableHead className="text-right">{t.check.pingColumns.exit}</TableHead>
            <TableHead className="text-right">{t.check.pingColumns.duration}</TableHead>
            <TableHead className="hidden lg:table-cell">{t.check.pingColumns.source}</TableHead>
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
  );
}

function PingRow({ ping, now }: { ping: Ping; now: number }) {
  return (
    <TableRow className="align-top">
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
      <TableCell className="hidden max-w-56 truncate text-muted-foreground text-xs lg:table-cell">
        {[ping.remoteAddr, ping.userAgent].filter(Boolean).join(" · ")}
      </TableCell>
      <TableCell className="max-w-64 pr-4 sm:max-w-md">
        {ping.body ? (
          <details>
            <summary className="cursor-pointer truncate font-mono text-muted-foreground text-xs">
              {ping.body.split("\n").findLast((line) => line.trim()) ?? ping.body}
            </summary>
            <pre className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap break-all rounded bg-surface p-2 text-xs">
              {ping.body}
            </pre>
          </details>
        ) : null}
      </TableCell>
    </TableRow>
  );
}
