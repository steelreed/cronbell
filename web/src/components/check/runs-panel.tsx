import { Bar, BarChart, Cell, ReferenceLine, XAxis, YAxis } from "recharts";
import { type ChartConfig, ChartContainer, ChartTooltip } from "@/components/ui/chart";
import { formatDuration, formatExact, formatPercent } from "@/lib/format";
import { axisTop, type Run, runTicks } from "@/lib/runs";
import { t } from "@/lib/strings";
import type { Check } from "@/lib/types";
import { EmptyLine, Panel } from "../page";
import { RunBar } from "../run-bar";

const DESKTOP_TICKS = 90;
const PHONE_TICKS = 40;
const DURATION_RUNS = 40;

const config = {
  duration: { label: t.check.durations, color: "var(--chart-1)" },
} satisfies ChartConfig;

/**
 * Run history as ticks (with the runs it missed, from `missed`), the check's uptime and
 * average run, then how long the recent runs took.
 */
export function RunsPanel({
  check,
  runs,
  missed,
}: {
  check: Check;
  runs: Run[];
  missed: string[];
}) {
  const shown = Math.min(DESKTOP_TICKS, Math.max(runs.length + missed.length, 1));
  return (
    <Panel title={t.check.runs} lead={runs.length > 0 ? t.check.runsLead : undefined}>
      {runs.length === 0 && check.status !== "DOWN" ? (
        <EmptyLine>{t.check.noPings}</EmptyLine>
      ) : (
        <>
          <Figures check={check} />
          <RunBar
            ticks={runTicks(runs, check, Math.max(shown, 30), missed)}
            size="lg"
            className="hidden sm:flex"
          />
          <RunBar
            ticks={runTicks(runs, check, PHONE_TICKS, missed)}
            size="lg"
            className="sm:hidden"
          />
          <Durations check={check} runs={runs} />
        </>
      )}
    </Panel>
  );
}

function Figures({ check }: { check: Check }) {
  const stats = check.stats;
  if (!stats) return null;
  const figures = [
    {
      label: t.check.stats.uptime7d,
      value: stats.uptime7d !== null ? formatPercent(stats.uptime7d) : null,
    },
    {
      label: t.check.stats.uptime30d,
      value: stats.uptime30d !== null ? formatPercent(stats.uptime30d) : null,
    },
    { label: t.check.stats.averageRun, value: formatDuration(stats.averageDurationMs7d) || null },
  ];
  return (
    <dl className="mb-4 grid grid-cols-3 gap-4 sm:flex sm:gap-10">
      {figures.map((figure) => (
        <div key={figure.label} className="min-w-0">
          <dt className="text-muted-foreground text-xs">{figure.label}</dt>
          <dd className="mt-0.5 font-medium text-base tabular-nums">
            {figure.value ?? <span className="text-muted-foreground">—</span>}
          </dd>
        </div>
      ))}
    </dl>
  );
}

function Durations({ check, runs }: { check: Check; runs: Run[] }) {
  const timed = runs.filter((run) => run.durationMs !== null).slice(-DURATION_RUNS);
  const longest = Math.max(0, ...timed.map((run) => run.durationMs ?? 0));
  // The max runtime line is drawn only when it is near the runs; an hour-long limit over
  // 30-second runs would flatten every bar.
  const limitMs =
    check.maxRuntimeSeconds !== null && check.maxRuntimeSeconds * 1000 <= longest * 2
      ? check.maxRuntimeSeconds * 1000
      : null;
  const top = axisTop(Math.max(longest, limitMs ?? 0));
  return (
    <div className="mt-6 border-t pt-4">
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h3 className="font-medium text-sm">{t.check.durations}</h3>
        {timed.length > 0 && (
          <p className="text-muted-foreground text-xs">{t.check.durationsLead}</p>
        )}
      </div>
      {timed.length === 0 ? (
        <p className="text-muted-foreground text-sm">{t.check.noDurations}</p>
      ) : (
        <ChartContainer
          config={config}
          className="aspect-auto h-40 w-full"
          role="img"
          aria-label={`${t.check.durations}: ${timed.length} runs`}
        >
          <BarChart
            data={timed.map((run) => ({ ...run, duration: run.durationMs ?? 0 }))}
            margin={{ top: 8, right: 4, bottom: 8, left: 0 }}
            barCategoryGap="20%"
          >
            <XAxis dataKey="at" hide />
            <YAxis
              width={56}
              axisLine={false}
              tickLine={false}
              ticks={[0, top / 2, top]}
              interval={0}
              tickFormatter={(value: number) => formatDuration(value)}
              domain={[0, top]}
            />
            <ChartTooltip
              cursor={{ fill: "var(--accent)" }}
              content={({ active, payload }) => {
                const run = active ? (payload?.[0]?.payload as Run | undefined) : undefined;
                if (!run) return null;
                return (
                  <div className="rounded-lg border bg-popover px-2.5 py-1.5 text-xs shadow-md">
                    <p className="text-muted-foreground">{formatExact(run.at)}</p>
                    <p className="mt-0.5 font-medium tabular-nums">
                      {formatDuration(run.durationMs)}
                      <span className={run.ok ? "text-ok" : "text-problem"}>
                        {" "}
                        · {run.ok ? t.runs.ok : t.runs.fail}
                      </span>
                    </p>
                  </div>
                );
              }}
            />
            {limitMs !== null && (
              <ReferenceLine
                y={limitMs}
                stroke="var(--warn)"
                strokeWidth={1}
                ifOverflow="extendDomain"
                label={{
                  value: t.check.maxRuntime,
                  position: "insideTopRight",
                  fill: "var(--muted-foreground)",
                  fontSize: 11,
                }}
              />
            )}
            <Bar dataKey="duration" radius={[3, 3, 0, 0]} maxBarSize={24} isAnimationActive={false}>
              {timed.map((run) => (
                <Cell key={run.at} fill={run.ok ? "var(--chart-1)" : "var(--problem)"} />
              ))}
            </Bar>
          </BarChart>
        </ChartContainer>
      )}
    </div>
  );
}
