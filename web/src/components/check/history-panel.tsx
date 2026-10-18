import { useState } from "react";
import { Button } from "@/components/ui/button";
import { formatClock, formatDuration, formatExact } from "@/lib/format";
import { downReason, type Episode, episodeMs, episodes } from "@/lib/history";
import { t } from "@/lib/strings";
import type { CheckAlert, DeliveryStatus, StatusEvent } from "@/lib/types";
import { cn } from "@/lib/utils";
import { EmptyLine, Panel } from "../page";
import { StatusLabel } from "../status-text";

const STEP = 8;

const DELIVERY_MARK: Record<DeliveryStatus, { mark: string; className: string }> = {
  DELIVERED: { mark: "✓", className: "text-ok" },
  FAILED: { mark: "✗", className: "text-problem" },
  PENDING: { mark: "…", className: "" },
};

/** The check's downtime and pauses, newest first, with where each DOWN alert went. */
export function HistoryPanel({
  events,
  alerts,
  now,
}: {
  events: StatusEvent[];
  alerts: CheckAlert[];
  now: number;
}) {
  const [shown, setShown] = useState(STEP);
  const rows = episodes(events, alerts);
  return (
    <Panel
      title={t.check.history.title}
      lead={rows.length > 0 ? t.check.history.lead : undefined}
      flush
    >
      {rows.length === 0 ? (
        <EmptyLine>{t.check.history.empty}</EmptyLine>
      ) : (
        <>
          <ul className="divide-y border-t">
            {rows.slice(0, shown).map((episode) => (
              <EpisodeRow key={episode.start.id} episode={episode} now={now} />
            ))}
          </ul>
          {rows.length > shown && (
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

function Moment({ iso, now }: { iso: string; now: number }) {
  return (
    <time dateTime={iso} title={formatExact(iso)}>
      {formatClock(iso, now)}
    </time>
  );
}

function ending(episode: Episode, now: number): string {
  const { start, end } = episode;
  if (!end) return start.kind === "DOWN" ? t.check.history.stillDown : t.check.history.stillPaused;
  const time = formatClock(end.at, now);
  if (end.kind === "PAUSED") return t.check.history.pausedAt(time);
  return start.kind === "DOWN" ? t.check.history.recovered(time) : t.check.history.resumed(time);
}

function EpisodeRow({ episode, now }: { episode: Episode; now: number }) {
  const { start } = episode;
  const down = start.kind === "DOWN";
  const why = down ? downReason(start, now) : null;
  return (
    <li className="px-4 py-3 sm:px-5">
      <div className="flex min-w-0 items-center gap-3">
        <StatusLabel
          view={{
            label: down ? t.check.history.down : t.check.history.paused,
            reason: null,
            tone: down ? "problem" : "warn",
          }}
          className="shrink-0"
        />
        {why && <span className="min-w-0 truncate text-sm">{why}</span>}
      </div>
      <p className="mt-0.5 text-muted-foreground text-xs tabular-nums">
        <Moment iso={start.at} now={now} />
        {" → "}
        <span className={cn(!episode.end && down && "text-problem")}>{ending(episode, now)}</span>
        {` (${formatDuration(episodeMs(episode, now))})`}
      </p>
      {episode.alerts.length > 0 && (
        <p className="mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-muted-foreground text-xs">
          <span>{t.check.history.alerted}</span>
          {episode.alerts.map((alert) => (
            <span key={alert.id} title={alert.lastError ?? undefined}>
              {alert.channelName}{" "}
              <span aria-hidden className={DELIVERY_MARK[alert.status].className}>
                {DELIVERY_MARK[alert.status].mark}
              </span>
              <span className="sr-only">{t.check.history.delivery[alert.status]}</span>
            </span>
          ))}
        </p>
      )}
    </li>
  );
}
