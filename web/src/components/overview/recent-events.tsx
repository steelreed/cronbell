import { Link } from "@tanstack/react-router";
import { ChevronRightIcon } from "lucide-react";
import { formatDuration } from "@/lib/format";
import { downReason } from "@/lib/history";
import type { Tone } from "@/lib/status";
import { t } from "@/lib/strings";
import type { StatusEvent, StatusEventKind } from "@/lib/types";
import { Panel } from "../page";
import { StatusLabel } from "../status-text";
import { Time } from "../time";

const SHOWN = 6;

const KIND_VIEW: Record<StatusEventKind, { label: string; tone: Tone }> = {
  DOWN: { label: t.status.DOWN, tone: "problem" },
  UP: { label: t.status.UP, tone: "ok" },
  PAUSED: { label: t.status.PAUSED, tone: "warn" },
  RESUMED: { label: t.overview.events.resumed, tone: "muted" },
};

/** What an event means, from the check's change before it when that is in the list. */
function detail(event: StatusEvent, earlier: StatusEvent | undefined, now: number): string | null {
  if (event.kind === "DOWN") return downReason(event, now);
  if (event.kind === "UP" && earlier?.kind === "DOWN") {
    return t.overview.events.recoveredAfter(
      formatDuration(Date.parse(event.at) - Date.parse(earlier.at)),
    );
  }
  if (event.kind === "UP" && earlier?.kind === "PAUSED") return t.overview.events.upAfterPause;
  return null;
}

/** The project's latest status changes, newest first, each opening its check. */
export function RecentEvents({ events, now }: { events: StatusEvent[]; now: number }) {
  return (
    <Panel title={t.overview.events.title} flush>
      {events.length === 0 ? (
        <p className="px-4 pt-2 pb-8 text-center text-muted-foreground text-sm sm:px-5">
          {t.overview.events.empty}
        </p>
      ) : (
        <ul className="mt-1 divide-y border-t">
          {events.slice(0, SHOWN).map((event, i) => {
            const earlier = events.slice(i + 1).find((other) => other.slug === event.slug);
            const what = detail(event, earlier, now);
            return (
              <li key={event.id}>
                <Link
                  to="/checks/$slug"
                  params={{ slug: event.slug }}
                  className="group flex items-center gap-3 px-4 py-3 transition-colors hover:bg-accent sm:px-5"
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex min-w-0 items-center gap-3">
                      <span className="min-w-0 truncate font-medium text-sm">{event.name}</span>
                      <StatusLabel
                        view={{ ...KIND_VIEW[event.kind], reason: null }}
                        className="shrink-0"
                      />
                    </div>
                    <p className="mt-0.5 truncate text-muted-foreground text-xs">
                      {what && `${what} · `}
                      <Time iso={event.at} now={now} />
                    </p>
                  </div>
                  <ChevronRightIcon
                    aria-hidden
                    className="size-4 shrink-0 text-muted-foreground transition-colors group-hover:text-foreground"
                  />
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </Panel>
  );
}
