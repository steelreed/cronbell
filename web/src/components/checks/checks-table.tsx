import { Link, useNavigate } from "@tanstack/react-router";
import { ArrowDownIcon, ArrowUpDownIcon, ArrowUpIcon } from "lucide-react";
import type { ReactNode } from "react";
import type { SortDir, SortKey } from "@/lib/checks-table";
import { formatSchedule } from "@/lib/format";
import { runTicks } from "@/lib/runs";
import { t } from "@/lib/strings";
import type { Check, RecentRuns } from "@/lib/types";
import { cn } from "@/lib/utils";
import { NextDue } from "../next-due";
import { Bar } from "../page";
import { RunBar } from "../run-bar";
import { StatusText } from "../status-text";
import { Time } from "../time";
import { CheckActionsMenu } from "./row-actions";

export const LIST_RUN_TICKS = 20;

export interface SortState {
  sort: SortKey;
  dir: SortDir;
  onSort: (key: SortKey) => void;
}

function SortHeader({
  label,
  sortKey,
  state,
  className,
}: {
  label: string;
  sortKey: SortKey;
  state: SortState;
  className?: string;
}) {
  const active = state.sort === sortKey;
  const Icon = !active ? ArrowUpDownIcon : state.dir === "asc" ? ArrowUpIcon : ArrowDownIcon;
  return (
    <th
      scope="col"
      aria-sort={active ? (state.dir === "asc" ? "ascending" : "descending") : undefined}
      className={cn("p-0 text-left font-medium", className)}
    >
      <button
        type="button"
        onClick={() => state.onSort(sortKey)}
        className={cn(
          "flex h-10 w-full items-center gap-1 whitespace-nowrap px-4 text-xs transition-colors hover:text-foreground",
          active ? "text-foreground" : "text-muted-foreground",
        )}
      >
        {label}
        <Icon aria-hidden className={cn("size-3.5", !active && "opacity-60")} />
      </button>
    </th>
  );
}

function PlainHeader({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <th
      scope="col"
      className={cn(
        "h-10 whitespace-nowrap px-4 text-left font-medium text-muted-foreground text-xs",
        className,
      )}
    >
      {children}
    </th>
  );
}

function Schedule({ check }: { check: Check }) {
  return (
    <>
      <span className="block truncate font-mono text-[13px]">{formatSchedule(check)}</span>
      <span className="block truncate text-muted-foreground text-xs">{check.timeZone}</span>
    </>
  );
}

/**
 * The checks table (from md up) and the same rows as a list on phones. The table sits in a
 * container: secondary columns drop out as the container narrows, never the name, status or
 * the row's actions.
 */
export function ChecksTable({
  checks,
  runs,
  now,
  sort,
}: {
  checks: Check[];
  runs: RecentRuns | undefined;
  now: number;
  sort: SortState;
}) {
  const navigate = useNavigate();
  const ticks = (check: Check) => runTicks(runs?.[check.slug] ?? [], check, LIST_RUN_TICKS);
  return (
    <>
      <div className="@container hidden md:block">
        <table className="w-full table-fixed border-collapse text-sm">
          <thead className="border-b">
            <tr>
              <SortHeader label={t.checks.columns.name} sortKey="name" state={sort} />
              <PlainHeader className="hidden w-44 @4xl:table-cell">
                {t.checks.columns.schedule}
              </PlainHeader>
              <SortHeader
                label={t.checks.columns.status}
                sortKey="status"
                state={sort}
                className="w-48"
              />
              <PlainHeader className="hidden w-40 @3xl:table-cell">
                {t.checks.columns.runs}
              </PlainHeader>
              <SortHeader
                label={t.checks.columns.lastPing}
                sortKey="lastPing"
                state={sort}
                className="w-32"
              />
              <SortHeader
                label={t.checks.columns.nextRun}
                sortKey="nextRun"
                state={sort}
                className="hidden w-44 @2xl:table-cell"
              />
              <th scope="col" className="w-14">
                <span className="sr-only">{t.common.actions}</span>
              </th>
            </tr>
          </thead>
          <tbody className="divide-y">
            {checks.map((check) => (
              <tr
                key={check.slug}
                className="group cursor-pointer transition-colors hover:bg-accent has-[[aria-expanded=true]]:bg-accent"
                onClick={(event) => {
                  if ((event.target as HTMLElement).closest("a,button")) return;
                  void navigate({ to: "/checks/$slug", params: { slug: check.slug } });
                }}
              >
                <td className="px-4 py-3">
                  <Link
                    to="/checks/$slug"
                    params={{ slug: check.slug }}
                    className="block truncate font-medium underline-offset-4 hover:underline"
                    title={check.name}
                  >
                    {check.name}
                  </Link>
                  {check.name !== check.slug && (
                    <span className="block truncate font-mono text-muted-foreground text-xs">
                      {check.slug}
                    </span>
                  )}
                </td>
                <td className="hidden px-4 py-3 @4xl:table-cell">
                  <Schedule check={check} />
                </td>
                <td className="px-4 py-3">
                  <StatusText check={check} now={now} className="max-w-full" />
                </td>
                <td className="hidden px-4 py-3 @3xl:table-cell">
                  {runs ? <RunBar ticks={ticks(check)} /> : <Bar className="h-5 w-28" />}
                </td>
                <td className="px-4 py-3 text-muted-foreground">
                  <Time iso={check.lastPingAt} now={now} />
                </td>
                <td className="hidden px-4 py-3 @2xl:table-cell">
                  <NextDue check={check} now={now} />
                </td>
                <td className="py-3 pr-3 pl-1 text-right">
                  <CheckActionsMenu check={check} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ul className="divide-y md:hidden">
        {checks.map((check) => (
          <li
            key={check.slug}
            className="relative flex items-start gap-2 py-3 pr-2 pl-4 transition-colors hover:bg-accent"
          >
            <Link
              to="/checks/$slug"
              params={{ slug: check.slug }}
              className="min-w-0 flex-1 space-y-1.5 after:absolute after:inset-0"
            >
              <span className="flex items-baseline justify-between gap-3">
                <span className="truncate font-medium">{check.name}</span>
              </span>
              <StatusText check={check} now={now} className="max-w-full" />
              <span className="flex items-center justify-between gap-3 text-muted-foreground text-xs">
                <span className="truncate font-mono">
                  {formatSchedule(check)} · {check.timeZone}
                </span>
                <span className="shrink-0">
                  <Time iso={check.lastPingAt} now={now} />
                </span>
              </span>
              {runs && <RunBar ticks={ticks(check)} className="h-4" />}
            </Link>
            <CheckActionsMenu check={check} triggerClassName="relative z-10" />
          </li>
        ))}
      </ul>
    </>
  );
}

/** Skeleton with the table's own rows and columns. */
export function ChecksTableSkeleton({ rows = 8 }: { rows?: number }) {
  return (
    <div aria-busy="true">
      <span className="sr-only" role="status">
        {t.common.loading}
      </span>
      <div className="h-10 border-b" />
      <ul className="divide-y">
        {Array.from({ length: rows }, (_, i) => (
          // biome-ignore lint/suspicious/noArrayIndexKey: placeholders have no identity
          <li key={i} className="flex items-center gap-6 px-4 py-4">
            <div className="min-w-0 flex-1 space-y-2">
              <Bar className={i % 3 ? "w-1/3" : "w-1/2"} />
              <Bar className="w-1/5" />
            </div>
            <Bar className="hidden w-20 sm:block" />
            <Bar className="hidden h-5 w-28 lg:block" />
            <Bar className="w-14" />
          </li>
        ))}
      </ul>
    </div>
  );
}
