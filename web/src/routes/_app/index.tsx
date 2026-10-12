import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { PlusIcon } from "lucide-react";
import { CodeBlock } from "@/components/copy-button";
import { NextDue } from "@/components/next-due";
import { ErrorNote, LoadingRows, PageHeader } from "@/components/page";
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
import { formatSchedule } from "@/lib/format";
import { checksQuery } from "@/lib/queries";
import { firstCheckSnippets } from "@/lib/snippets";
import { STATUS_ORDER } from "@/lib/status";
import { t } from "@/lib/strings";
import type { Check, CheckStatus, Session } from "@/lib/types";
import { useNow } from "@/lib/use-now";
import { cn } from "@/lib/utils";
import { browserTimeZone } from "@/lib/validation";

type Filter = "all" | CheckStatus;
const FILTERS: Filter[] = ["all", "DOWN", "UP", "NEW", "PAUSED"];

export const Route = createFileRoute("/_app/")({
  validateSearch: (search: Record<string, unknown>): { status?: CheckStatus } =>
    typeof search.status === "string" && FILTERS.includes(search.status as Filter)
      ? { status: search.status as CheckStatus }
      : {},
  component: ChecksPage,
});

function ChecksPage() {
  const { session } = Route.useRouteContext();
  const { status } = Route.useSearch();
  const checks = useQuery(checksQuery);
  const now = useNow();
  const filter: Filter = status ?? "all";

  const add = (
    <Button asChild>
      <Link to="/checks/new">
        <PlusIcon />
        {t.checks.add}
      </Link>
    </Button>
  );

  if (checks.isPending) {
    return (
      <>
        <PageHeader title={t.checks.title} actions={add} />
        <LoadingRows />
      </>
    );
  }
  if (checks.isError) {
    return (
      <>
        <PageHeader title={t.checks.title} actions={add} />
        <ErrorNote error={checks.error} onRetry={() => checks.refetch()} />
      </>
    );
  }
  if (checks.data.length === 0) {
    return (
      <>
        <PageHeader title={t.checks.title} actions={add} />
        <EmptyState session={session} />
      </>
    );
  }

  const counts = countByStatus(checks.data);
  const visible = checks.data
    .filter((check) => filter === "all" || check.status === filter)
    .sort(
      (a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status] || a.slug.localeCompare(b.slug),
    );

  return (
    <>
      <PageHeader title={t.checks.title} actions={add} />
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <nav className="flex flex-wrap gap-x-4 gap-y-1 text-sm" aria-label="Filter by status">
          {FILTERS.map((item) => (
            <Link
              key={item}
              to="/"
              search={item === "all" ? {} : { status: item }}
              aria-current={filter === item ? "true" : undefined}
              className={cn(
                "border-transparent border-b py-1 text-muted-foreground hover:text-foreground",
                filter === item && "border-foreground text-foreground",
              )}
            >
              {item === "all" ? t.checks.filters.all : t.checks.filters[item]}{" "}
              <span className="text-muted-foreground tabular-nums">
                {item === "all" ? checks.data.length : counts[item]}
              </span>
            </Link>
          ))}
        </nav>
        <span className="text-muted-foreground text-xs">{t.checks.refreshed}</span>
      </div>

      {visible.length === 0 ? (
        <p className="rounded-md border px-4 py-10 text-center text-muted-foreground text-sm">
          {t.checks.noneMatch}
        </p>
      ) : (
        <>
          <div className="hidden rounded-md border md:block">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">{t.checks.columns.name}</TableHead>
                  <TableHead>{t.checks.columns.schedule}</TableHead>
                  <TableHead>{t.checks.columns.status}</TableHead>
                  <TableHead>{t.checks.columns.lastPing}</TableHead>
                  <TableHead className="pr-4">{t.checks.columns.nextDue}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {visible.map((check) => (
                  <TableRow key={check.slug} className="group">
                    <TableCell className="max-w-72 pl-4">
                      <Link
                        to="/checks/$slug"
                        params={{ slug: check.slug }}
                        className="block truncate font-medium group-hover:underline underline-offset-4"
                      >
                        {check.name}
                      </Link>
                      {check.name !== check.slug && (
                        <span className="block truncate font-mono text-muted-foreground text-xs">
                          {check.slug}
                        </span>
                      )}
                    </TableCell>
                    <TableCell>
                      <span className="font-mono text-[13px]">{formatSchedule(check)}</span>
                      <span className="block text-muted-foreground text-xs">{check.timeZone}</span>
                    </TableCell>
                    <TableCell>
                      <StatusText check={check} now={now} />
                    </TableCell>
                    <TableCell>
                      <Time iso={check.lastPingAt} now={now} />
                    </TableCell>
                    <TableCell className="pr-4">
                      <NextDue check={check} now={now} />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <ul className="divide-y rounded-md border md:hidden">
            {visible.map((check) => (
              <li key={check.slug}>
                <Link
                  to="/checks/$slug"
                  params={{ slug: check.slug }}
                  className="block space-y-1 px-4 py-3 hover:bg-accent/50"
                >
                  <div className="flex items-baseline justify-between gap-3">
                    <span className="truncate font-medium">{check.name}</span>
                    <StatusText check={check} now={now} className="shrink-0" />
                  </div>
                  <div className="flex items-baseline justify-between gap-3 text-muted-foreground text-xs">
                    <span className="truncate font-mono">
                      {formatSchedule(check)} · {check.timeZone}
                    </span>
                    <span className="shrink-0">
                      {t.checks.columns.lastPing.toLowerCase()}{" "}
                      <Time iso={check.lastPingAt} now={now} />
                    </span>
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}

function countByStatus(checks: Check[]): Record<CheckStatus, number> {
  const counts: Record<CheckStatus, number> = { NEW: 0, UP: 0, DOWN: 0, PAUSED: 0 };
  for (const check of checks) counts[check.status]++;
  return counts;
}

function EmptyState({ session }: { session: Session }) {
  const snippets = firstCheckSnippets(
    window.location.origin,
    session.pingBaseUrl,
    browserTimeZone(),
  );
  return (
    <section className="rounded-md border p-5 sm:p-6">
      <h2 className="font-semibold">{t.checks.empty.title}</h2>
      <p className="mt-1 max-w-2xl text-muted-foreground text-sm">{t.checks.empty.lead}</p>
      <ol className="mt-6 space-y-6">
        <li>
          <p className="mb-2 text-sm">
            <span className="mr-2 font-mono text-muted-foreground">1</span>
            {t.checks.empty.step1} (
            <Link to="/keys" className="underline underline-offset-4">
              {t.checks.empty.keyHint}
            </Link>
            ), {t.checks.empty.formHint}{" "}
            <Link to="/checks/new" className="underline underline-offset-4">
              {t.checks.add.toLowerCase()}
            </Link>
            .
          </p>
          <CodeBlock code={snippets.create} />
        </li>
        <li>
          <p className="mb-2 text-sm">
            <span className="mr-2 font-mono text-muted-foreground">2</span>
            {t.checks.empty.step2}
          </p>
          <CodeBlock code={snippets.ping} />
        </li>
      </ol>
    </section>
  );
}
