import { ChevronRightIcon } from "lucide-react";
import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { Toggle } from "@/components/ui/toggle";
import { formatDuration } from "@/lib/format";
import { groupRuns, type RunGroup, type RunResult } from "@/lib/runs";
import { t } from "@/lib/strings";
import type { Ping, PingKind } from "@/lib/types";
import { cn } from "@/lib/utils";
import { EmptyLine, Panel } from "../page";
import { Time } from "../time";

const STEP = 12;

const KIND_TONE: Record<PingKind, string> = {
  START: "text-muted-foreground",
  SUCCESS: "text-ok",
  FAIL: "text-problem",
  LOG: "text-muted-foreground",
};

const RESULT_TONE: Record<RunResult, string> = {
  ok: "text-ok",
  fail: "text-problem",
  running: "text-muted-foreground",
  unfinished: "text-muted-foreground",
  log: "text-muted-foreground",
};

/**
 * Latest runs, newest first, each a start with its finish; or every ping, raw. A table from md
 * up, cards on phones; output on demand.
 */
export function PingsPanel({ pings, now }: { pings: Ping[]; now: number }) {
  const [raw, setRaw] = useState(false);
  const [shown, setShown] = useState(STEP);
  const runs = useMemo(() => groupRuns(pings), [pings]);
  const total = raw ? pings.length : runs.length;
  return (
    <Panel
      title={raw ? t.check.pings : t.check.recentRuns}
      flush
      action={
        pings.length > 0 && (
          <Toggle
            variant="outline"
            size="sm"
            pressed={raw}
            onPressedChange={setRaw}
            title={t.check.rawPingsHint}
          >
            {t.check.rawPings}
          </Toggle>
        )
      }
    >
      {pings.length === 0 ? (
        <EmptyLine>{t.check.noPings}</EmptyLine>
      ) : (
        <>
          {raw ? (
            <PingList pings={pings.slice(0, shown)} now={now} />
          ) : (
            <RunList runs={runs.slice(0, shown)} now={now} />
          )}
          {total > shown && (
            <div className="border-t px-2 py-2 sm:px-3">
              <Button variant="ghost" size="sm" onClick={() => setShown(shown + STEP)}>
                {t.common.showMore}
              </Button>
            </div>
          )}
        </>
      )}
    </Panel>
  );
}

function RunList({ runs, now }: { runs: RunGroup[]; now: number }) {
  return (
    <>
      <div className="hidden border-t md:block">
        <table className="w-full table-fixed text-sm">
          <thead className="border-b">
            <tr className="text-left text-muted-foreground text-xs">
              <th scope="col" className="h-10 w-32 px-4 font-medium sm:pl-5">
                {t.check.runColumns.started}
              </th>
              <th scope="col" className="w-36 px-2 font-medium">
                {t.check.runColumns.result}
              </th>
              <th scope="col" className="w-24 px-2 text-right font-medium">
                {t.check.runColumns.duration}
              </th>
              <th scope="col" className="px-4 font-medium sm:pr-5">
                {t.check.runColumns.output}
              </th>
            </tr>
          </thead>
          <tbody className="divide-y">
            {runs.map((run) => (
              <RunRow key={run.id} run={run} now={now} />
            ))}
          </tbody>
        </table>
      </div>
      <ul className="divide-y border-t md:hidden">
        {runs.map((run) => (
          <RunItem key={run.id} run={run} now={now} />
        ))}
      </ul>
    </>
  );
}

function RunResultText({ run }: { run: RunGroup }) {
  return (
    <span className={cn("font-mono text-xs", RESULT_TONE[run.result])}>
      {t.check.runResults[run.result]}
      {run.result === "fail" && run.exitCode !== null && ` · ${t.runs.exit(run.exitCode)}`}
    </span>
  );
}

function RunRow({ run, now }: { run: RunGroup; now: number }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <tr className={cn(open && "border-b-0")}>
        <td className="px-4 py-2.5 sm:pl-5">
          <Time iso={run.startedAt} now={now} />
        </td>
        <td className="px-2 py-2.5">
          <RunResultText run={run} />
        </td>
        <td className="px-2 py-2.5 text-right text-xs tabular-nums">
          {formatDuration(run.durationMs)}
        </td>
        <td className="px-4 py-2.5 sm:pr-5">
          <OutputToggle body={run.output} open={open} onToggle={() => setOpen(!open)} />
        </td>
      </tr>
      {open && run.output && (
        <tr>
          <td colSpan={4} className="px-4 pt-0 pb-3 sm:px-5">
            <Output body={run.output} />
          </td>
        </tr>
      )}
    </>
  );
}

/** One run on a phone: result, duration and start time; output below, on demand. */
function RunItem({ run, now }: { run: RunGroup; now: number }) {
  const [open, setOpen] = useState(false);
  return (
    <li className="space-y-1.5 px-4 py-3">
      <div className="flex items-baseline justify-between gap-3">
        <span className="min-w-0 truncate text-xs">
          <RunResultText run={run} />
          {run.durationMs !== null && (
            <span className="text-muted-foreground tabular-nums">
              {" · "}
              {formatDuration(run.durationMs)}
            </span>
          )}
        </span>
        <span className="shrink-0 text-muted-foreground text-xs">
          <Time iso={run.startedAt} now={now} />
        </span>
      </div>
      {run.output && <OutputToggle body={run.output} open={open} onToggle={() => setOpen(!open)} />}
      {open && run.output && <Output body={run.output} />}
    </li>
  );
}

function PingList({ pings, now }: { pings: Ping[]; now: number }) {
  return (
    <>
      <div className="hidden border-t md:block">
        <table className="w-full table-fixed text-sm">
          <thead className="border-b">
            <tr className="text-left text-muted-foreground text-xs">
              <th scope="col" className="h-10 w-32 px-4 font-medium sm:pl-5">
                {t.check.pingColumns.time}
              </th>
              <th scope="col" className="w-20 px-2 font-medium">
                {t.check.pingColumns.kind}
              </th>
              <th scope="col" className="w-14 px-2 text-right font-medium">
                {t.check.pingColumns.exit}
              </th>
              <th scope="col" className="w-24 px-2 text-right font-medium">
                {t.check.pingColumns.duration}
              </th>
              <th scope="col" className="hidden w-44 px-4 font-medium xl:table-cell">
                {t.check.pingColumns.source}
              </th>
              <th scope="col" className="px-4 font-medium sm:pr-5">
                {t.check.pingColumns.body}
              </th>
            </tr>
          </thead>
          <tbody className="divide-y">
            {pings.map((ping) => (
              <PingRow key={ping.id} ping={ping} now={now} />
            ))}
          </tbody>
        </table>
      </div>
      <ul className="divide-y border-t md:hidden">
        {pings.map((ping) => (
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
  body,
  open,
  onToggle,
}: {
  body: string | null;
  open: boolean;
  onToggle: () => void;
}) {
  if (!body) return <span className="text-muted-foreground">—</span>;
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-expanded={open}
      aria-label={open ? t.check.hideOutput : t.check.showOutput}
      className="flex min-h-8 w-full min-w-0 items-center gap-1.5 text-left font-mono text-muted-foreground text-xs transition-colors hover:text-foreground"
    >
      <ChevronRightIcon
        aria-hidden
        className={cn(
          "size-3.5 shrink-0 transition-transform motion-reduce:transition-none",
          open && "rotate-90",
        )}
      />
      <span className="truncate">{lastLine(body)}</span>
    </button>
  );
}

function Output({ body }: { body: string }) {
  return (
    <pre className="max-h-96 overflow-auto whitespace-pre-wrap break-words rounded-lg border bg-page p-3 font-mono text-xs leading-relaxed">
      {body}
    </pre>
  );
}

function source(ping: Ping) {
  return [ping.remoteAddr, ping.userAgent].filter(Boolean).join(" · ");
}

function PingRow({ ping, now }: { ping: Ping; now: number }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <tr className={cn(open && "border-b-0")}>
        <td className="px-4 py-2.5 sm:pl-5">
          <Time iso={ping.receivedAt} now={now} />
        </td>
        <td className="px-2 py-2.5">
          <span className={cn("font-mono text-xs", KIND_TONE[ping.kind])}>
            {t.check.kinds[ping.kind]}
          </span>
        </td>
        <td
          className={cn(
            "px-2 py-2.5 text-right font-mono text-xs tabular-nums",
            ping.kind === "FAIL" && "text-problem",
          )}
        >
          {ping.exitCode ?? ""}
        </td>
        <td className="px-2 py-2.5 text-right text-xs tabular-nums">
          {formatDuration(ping.durationMs)}
        </td>
        <td
          className="hidden truncate px-4 py-2.5 text-muted-foreground text-xs xl:table-cell"
          title={source(ping)}
        >
          {source(ping)}
        </td>
        <td className="px-4 py-2.5 sm:pr-5">
          <OutputToggle body={ping.body} open={open} onToggle={() => setOpen(!open)} />
        </td>
      </tr>
      {open && ping.body && (
        <tr>
          <td colSpan={6} className="px-4 pt-0 pb-3 sm:px-5">
            <Output body={ping.body} />
          </td>
        </tr>
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
      {ping.body && <OutputToggle body={ping.body} open={open} onToggle={() => setOpen(!open)} />}
      {open && ping.body && <Output body={ping.body} />}
    </li>
  );
}
