import { Link } from "@tanstack/react-router";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { t } from "@/lib/strings";
import { dayTitle, problemsFirst, stripSummary } from "@/lib/strip";
import type { CheckDays, DayState } from "@/lib/types";
import { cn } from "@/lib/utils";

const LIMIT = 12;

const CELL_CLASS: Record<DayState, string> = {
  OK: "bg-ok",
  ISSUES: "bg-warn",
  DOWN: "bg-problem",
  NONE: "bg-foreground/10",
};

const LEGEND: DayState[] = ["OK", "ISSUES", "DOWN", "NONE"];

/**
 * Every check's last days as a row of squares, coloured by state only, problems first. The
 * name sits beside its row from sm up and above it on phones.
 */
export function StatusStrip({ checks }: { checks: CheckDays[] }) {
  const [all, setAll] = useState(false);
  const sorted = problemsFirst(checks);
  const shown = all ? sorted : sorted.slice(0, LIMIT);
  const days = checks[0]?.days.length ?? 0;
  const columns = { gridTemplateColumns: `repeat(${days}, minmax(0, 0.875rem))` };
  const half = Math.max(1, Math.floor(days / 2));
  return (
    <div>
      <ul className="mb-4 flex flex-wrap gap-x-4 gap-y-1 text-muted-foreground text-xs">
        {LEGEND.map((state) => (
          <li key={state} className="flex items-center gap-1.5">
            <span aria-hidden className={cn("size-2.5 rounded-[2px]", CELL_CLASS[state])} />
            {t.overview.strip.states[state]}
          </li>
        ))}
      </ul>
      <ul className="space-y-3 sm:space-y-2">
        {shown.map((check) => (
          <li
            key={check.slug}
            className="grid gap-1.5 sm:grid-cols-[11rem_minmax(0,1fr)] sm:items-center sm:gap-4"
          >
            <Link
              to="/checks/$slug"
              params={{ slug: check.slug }}
              className="min-w-0 truncate text-sm underline-offset-4 hover:underline"
            >
              {check.name}
            </Link>
            <div
              role="img"
              aria-label={`${check.name}: ${stripSummary(check.days)}`}
              className="grid gap-[3px]"
              style={columns}
            >
              {check.days.map((day) => (
                <span
                  key={day.date}
                  title={dayTitle(day)}
                  data-state={day.state}
                  className={cn("block aspect-square rounded-[2px]", CELL_CLASS[day.state])}
                />
              ))}
            </div>
          </li>
        ))}
      </ul>
      <div
        aria-hidden
        className="mt-2 grid gap-[3px] whitespace-nowrap text-muted-foreground text-xs sm:ml-[calc(11rem+1rem)]"
        style={columns}
      >
        <span style={{ gridColumn: `1 / span ${half}` }}>{t.overview.strip.start}</span>
        <span className="text-right" style={{ gridColumn: `${half + 1} / -1` }}>
          {t.overview.strip.end}
        </span>
      </div>
      {sorted.length > LIMIT && (
        <Button variant="ghost" size="sm" className="-ml-2 mt-3" onClick={() => setAll(!all)}>
          {all ? t.overview.strip.showFewer : t.overview.strip.showAll(sorted.length)}
        </Button>
      )}
    </div>
  );
}
