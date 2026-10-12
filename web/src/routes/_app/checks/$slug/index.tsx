import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { PauseIcon, PencilIcon, PlayIcon } from "lucide-react";
import { IntegratePanel } from "@/components/check/integrate-panel";
import { PingsPanel } from "@/components/check/pings-panel";
import { RunsPanel } from "@/components/check/runs-panel";
import { CheckActionsMenu } from "@/components/checks/row-actions";
import { Dash, DescriptionList } from "@/components/description-list";
import { NextDue } from "@/components/next-due";
import { Bar, ErrorNote, ErrorState, PageHeader, Panel } from "@/components/page";
import { StatusText } from "@/components/status-text";
import { Time } from "@/components/time";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api";
import { useCheckActions } from "@/lib/check-actions";
import { formatSchedule, formatSeconds } from "@/lib/format";
import { checkQuery, pingsQuery } from "@/lib/queries";
import { runsFromPings } from "@/lib/runs";
import { attention } from "@/lib/status";
import { t } from "@/lib/strings";
import type { Check } from "@/lib/types";
import { useNow } from "@/lib/use-now";

export const Route = createFileRoute("/_app/checks/$slug/")({
  component: CheckPage,
});

function CheckPage() {
  const { slug } = Route.useParams();
  const check = useQuery(checkQuery(slug));
  const now = useNow();
  if (check.isPending) return <CheckSkeleton />;
  if (check.isError) {
    const missing = check.error instanceof ApiError && check.error.status === 404;
    return missing ? (
      <div className="mx-auto max-w-md py-16 text-center">
        <h1 className="font-semibold text-xl">{t.check.notFound}</h1>
        <Button asChild className="mt-6">
          <Link to="/checks">{t.check.backToChecks}</Link>
        </Button>
      </div>
    ) : (
      <Panel>
        <ErrorState error={check.error} onRetry={() => check.refetch()} />
      </Panel>
    );
  }
  return <CheckView check={check.data} now={now} />;
}

function CheckView({ check, now }: { check: Check; now: number }) {
  const pings = useQuery(pingsQuery(check.slug));
  const runs = pings.data ? runsFromPings(pings.data) : [];
  return (
    <>
      <CheckHeader check={check} now={now} />
      <div className="@container">
        <div className="grid gap-4 @[64rem]:grid-cols-[minmax(0,1fr)_20rem]">
          <div className="min-w-0 space-y-4">
            {pings.isPending ? (
              <Panel title={t.check.runs}>
                <Bar className="h-9 w-full" />
                <Bar className="mt-8 h-32 w-full" />
              </Panel>
            ) : pings.isError ? (
              <Panel title={t.check.runs}>
                <ErrorState error={pings.error} onRetry={() => pings.refetch()} />
              </Panel>
            ) : (
              <>
                <RunsPanel check={check} runs={runs} />
                <PingsPanel pings={pings.data} now={now} />
              </>
            )}
            <IntegratePanel check={check} />
          </div>
          <div className="min-w-0 space-y-4 @[64rem]:order-none">
            <Panel title={t.check.details}>
              <DescriptionList
                items={[
                  {
                    label: t.check.schedule,
                    value: <span className="font-mono text-[13px]">{formatSchedule(check)}</span>,
                  },
                  { label: t.check.timeZone, value: check.timeZone },
                  { label: t.check.grace, value: formatSeconds(check.graceSeconds) },
                  {
                    label: t.check.maxRuntime,
                    value:
                      check.maxRuntimeSeconds !== null ? (
                        formatSeconds(check.maxRuntimeSeconds)
                      ) : (
                        <span className="text-muted-foreground">{t.check.sameAsGrace}</span>
                      ),
                  },
                  { label: t.check.nextRun, value: <NextDue check={check} now={now} /> },
                  {
                    label: t.check.lastSuccess,
                    value: check.lastSuccessAt ? (
                      <Time iso={check.lastSuccessAt} now={now} />
                    ) : (
                      <Dash />
                    ),
                  },
                  {
                    label: t.check.lastPing,
                    value: check.lastPingAt ? <Time iso={check.lastPingAt} now={now} /> : <Dash />,
                  },
                  { label: t.check.created, value: <Time iso={check.createdAt} now={now} /> },
                ]}
              />
            </Panel>
          </div>
        </div>
      </div>
    </>
  );
}

function CheckHeader({ check, now }: { check: Check; now: number }) {
  const navigate = useNavigate();
  const { toggle } = useCheckActions(check);
  const paused = check.status === "PAUSED";
  const issue = attention(check, now);
  return (
    <div className="mb-4">
      <PageHeader
        title={check.name}
        meta={
          <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <StatusText check={check} now={now} />
            {issue?.since && (
              <span className="text-muted-foreground">
                {t.check.since} <Time iso={issue.since} now={now} />
              </span>
            )}
            {check.name !== check.slug && <span className="font-mono text-xs">{check.slug}</span>}
            {paused && <span className="basis-full">{t.check.pausedNote}</span>}
          </span>
        }
        actions={
          <>
            <Button variant="outline" onClick={() => toggle.mutate()} disabled={toggle.isPending}>
              {paused ? <PlayIcon aria-hidden /> : <PauseIcon aria-hidden />}
              {paused ? t.check.resume : t.check.pause}
            </Button>
            <Button variant="outline" asChild>
              <Link to="/checks/$slug/edit" params={{ slug: check.slug }}>
                <PencilIcon aria-hidden />
                {t.common.edit}
              </Link>
            </Button>
            <CheckActionsMenu
              check={check}
              showOpen={false}
              triggerVariant="outline"
              onDeleted={() => void navigate({ to: "/checks" })}
            />
          </>
        }
      />
      {toggle.error && <ErrorNote error={toggle.error} />}
    </div>
  );
}

function CheckSkeleton() {
  return (
    <div aria-busy="true">
      <span className="sr-only" role="status">
        {t.common.loading}
      </span>
      <div className="mb-5 space-y-3">
        <Bar className="h-5 w-56" />
        <Bar className="w-32" />
      </div>
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <Panel title={t.check.runs}>
          <Bar className="h-9 w-full" />
          <Bar className="mt-8 h-32 w-full" />
        </Panel>
        <Panel title={t.check.details}>
          <div className="space-y-4">
            {Array.from({ length: 6 }, (_, i) => (
              // biome-ignore lint/suspicious/noArrayIndexKey: placeholders have no identity
              <Bar key={i} className="w-3/4" />
            ))}
          </div>
        </Panel>
      </div>
    </div>
  );
}
