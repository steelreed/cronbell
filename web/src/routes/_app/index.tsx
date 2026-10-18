import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { AttentionList } from "@/components/overview/attention-list";
import { FirstSteps } from "@/components/overview/first-steps";
import { RecentEvents } from "@/components/overview/recent-events";
import { StatTiles } from "@/components/overview/stat-tiles";
import { StatusStrip } from "@/components/overview/status-strip";
import { Bar, ErrorState, LoadingList, Panel } from "@/components/page";
import { checksQuery, projectEventsQuery, statusDaysQuery } from "@/lib/queries";
import { countByHealth, needsAttention } from "@/lib/status";
import { t } from "@/lib/strings";
import { useNow } from "@/lib/use-now";
import { browserTimeZone } from "@/lib/validation";

export const Route = createFileRoute("/_app/")({
  component: OverviewPage,
});

/** Events the feed reads: more than it shows, to tell a recovery from a first run. */
const EVENTS = 30;

/**
 * The overview: counts, what needs attention now, the latest status changes and how every
 * check spent the last 30 days, under the setup steps while the project is young. A project
 * without checks shows only the steps.
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
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_24rem] xl:grid-rows-[auto_1fr]">
        <div className="min-w-0 xl:col-start-2">
          <AttentionList items={needsAttention(checks.data, now)} now={now} />
        </div>
        <Panel
          title={t.overview.strip.title}
          lead={t.overview.strip.lead}
          className="xl:col-start-1 xl:row-span-2 xl:row-start-1 xl:self-start"
        >
          <Strip />
        </Panel>
        <div className="min-w-0 xl:col-start-2">
          <Events now={now} />
        </div>
      </div>
    </div>
  );
}

function Strip() {
  const days = useQuery(statusDaysQuery(browserTimeZone()));
  if (days.isPending) return <StripSkeleton />;
  if (days.isError) return <ErrorState error={days.error} onRetry={() => days.refetch()} />;
  return <StatusStrip checks={days.data} />;
}

function Events({ now }: { now: number }) {
  const events = useQuery(projectEventsQuery(EVENTS));
  if (events.isPending) {
    return (
      <Panel title={t.overview.events.title} flush>
        <LoadingList rows={3} />
      </Panel>
    );
  }
  if (events.isError) {
    return (
      <Panel title={t.overview.events.title}>
        <ErrorState error={events.error} onRetry={() => events.refetch()} />
      </Panel>
    );
  }
  return <RecentEvents events={events.data} now={now} />;
}

function StripSkeleton() {
  return (
    <div aria-busy="true" className="space-y-3">
      <span className="sr-only" role="status">
        {t.common.loading}
      </span>
      {Array.from({ length: 4 }, (_, i) => (
        // biome-ignore lint/suspicious/noArrayIndexKey: placeholders have no identity
        <Bar key={i} className="h-3.5 w-full" />
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
        <Panel title={t.overview.strip.title}>
          <StripSkeleton />
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
