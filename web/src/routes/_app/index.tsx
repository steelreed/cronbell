import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { AttentionList } from "@/components/overview/attention-list";
import { DailyChart } from "@/components/overview/daily-chart";
import { FirstSteps } from "@/components/overview/first-steps";
import { StatTiles } from "@/components/overview/stat-tiles";
import { Bar, EmptyLine, ErrorState, Panel } from "@/components/page";
import { checksQuery, dailyStatsQuery } from "@/lib/queries";
import { countByHealth, needsAttention } from "@/lib/status";
import { t } from "@/lib/strings";
import { useNow } from "@/lib/use-now";
import { browserTimeZone } from "@/lib/validation";

export const Route = createFileRoute("/_app/")({
  component: OverviewPage,
});

/**
 * The overview: counts, what needs attention now, and runs per day, under the setup steps
 * while the project is young. A project without checks shows only the steps.
 */
function OverviewPage() {
  const { session } = Route.useRouteContext();
  const checks = useQuery(checksQuery);
  const now = useNow();
  if (checks.isPending) return <OverviewSkeleton />;
  if (checks.isError) {
    return (
      <Panel>
        <ErrorState error={checks.error} onRetry={() => checks.refetch()} />
      </Panel>
    );
  }
  if (checks.data.length === 0) return <FirstSteps checks={checks.data} session={session} />;
  return (
    <div className="space-y-4">
      <FirstSteps checks={checks.data} session={session} />
      <StatTiles counts={countByHealth(checks.data, now)} />
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_24rem]">
        <Panel title={t.overview.chart}>
          <RunsChart />
        </Panel>
        <div className="order-first min-w-0 xl:order-none">
          <AttentionList items={needsAttention(checks.data, now)} now={now} />
        </div>
      </div>
    </div>
  );
}

function RunsChart() {
  const stats = useQuery(dailyStatsQuery(browserTimeZone()));
  if (stats.isPending) return <ChartSkeleton />;
  if (stats.isError) return <ErrorState error={stats.error} onRetry={() => stats.refetch()} />;
  if (stats.data.every((day) => day.runs === 0))
    return <EmptyLine>{t.overview.chartEmpty}</EmptyLine>;
  return <DailyChart days={stats.data} />;
}

function ChartSkeleton() {
  return (
    <div aria-busy="true" className="flex h-64 items-end gap-2 pt-8">
      <span className="sr-only" role="status">
        {t.common.loading}
      </span>
      {Array.from({ length: 14 }, (_, i) => (
        <span
          // biome-ignore lint/suspicious/noArrayIndexKey: placeholders have no identity
          key={i}
          className="block flex-1 animate-pulse rounded-t bg-foreground/[0.07] motion-reduce:animate-none"
          style={{ height: `${30 + ((i * 37) % 60)}%` }}
        />
      ))}
    </div>
  );
}

function OverviewSkeleton() {
  return (
    <div className="space-y-4" aria-busy="true">
      <span className="sr-only" role="status">
        {t.common.loading}
      </span>
      <div className="grid grid-cols-2 gap-px overflow-hidden rounded-xl border bg-border lg:grid-cols-4">
        {Array.from({ length: 4 }, (_, i) => (
          // biome-ignore lint/suspicious/noArrayIndexKey: placeholders have no identity
          <div key={i} className="space-y-3 bg-card p-4 sm:p-5">
            <Bar className="w-16" />
            <Bar className="h-6 w-10" />
            <Bar className="w-24" />
          </div>
        ))}
      </div>
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_24rem]">
        <Panel title={t.overview.chart}>
          <ChartSkeleton />
        </Panel>
        <Panel title={t.overview.attention}>
          <div className="space-y-5">
            {Array.from({ length: 4 }, (_, i) => (
              // biome-ignore lint/suspicious/noArrayIndexKey: placeholders have no identity
              <div key={i} className="space-y-2">
                <Bar className="w-1/2" />
                <Bar className="w-1/3" />
              </div>
            ))}
          </div>
        </Panel>
      </div>
    </div>
  );
}
