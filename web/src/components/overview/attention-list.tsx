import { Link } from "@tanstack/react-router";
import { ChevronRightIcon } from "lucide-react";
import type { Attention } from "@/lib/status";
import { t } from "@/lib/strings";
import { Panel } from "../page";
import { StatusLabel } from "../status-text";
import { Time } from "../time";

const LIMIT = 6;

/** DOWN checks first, then late ones, then checks never pinged: what, and since when. */
export function AttentionList({ items, now }: { items: Attention[]; now: number }) {
  return (
    <Panel
      title={t.overview.attention}
      flush
      action={
        items.length > LIMIT ? (
          <Link
            to="/checks"
            className="inline-flex h-8 items-center whitespace-nowrap font-medium text-foreground/70 text-sm underline-offset-4 hover:text-foreground hover:underline"
          >
            {t.overview.viewAll}
          </Link>
        ) : null
      }
    >
      {items.length === 0 ? (
        <p className="px-4 pt-2 pb-8 text-center text-muted-foreground text-sm sm:px-5">
          {t.overview.allGood}
        </p>
      ) : (
        <ul className="mt-1 divide-y border-t">
          {items.slice(0, LIMIT).map((item) => (
            <li key={item.check.slug}>
              <Link
                to="/checks/$slug"
                params={{ slug: item.check.slug }}
                className="group flex items-center gap-3 px-4 py-3 transition-colors hover:bg-accent sm:px-5"
              >
                <div className="min-w-0 flex-1">
                  <div className="flex min-w-0 items-center gap-3">
                    <span className="min-w-0 truncate font-medium text-sm">{item.check.name}</span>
                    <StatusLabel
                      view={{
                        label:
                          t.status[
                            item.health === "down"
                              ? "DOWN"
                              : item.health === "late"
                                ? "LATE"
                                : "NEW"
                          ],
                        reason: null,
                        tone: item.tone,
                      }}
                      className="shrink-0"
                    />
                  </div>
                  <p className="mt-0.5 truncate text-muted-foreground text-xs">
                    {item.reason}
                    {item.since && (
                      <>
                        {" · "}
                        {t.overview.since} <Time iso={item.since} now={now} />
                      </>
                    )}
                  </p>
                </div>
                <ChevronRightIcon
                  aria-hidden
                  className="size-4 shrink-0 text-muted-foreground transition-colors group-hover:text-foreground"
                />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
