import { Time } from "@/components/time";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { formatClock, formatExact } from "@/lib/format";
import { dueView } from "@/lib/status";
import { t } from "@/lib/strings";
import type { Check } from "@/lib/types";
import { cn } from "@/lib/utils";

/** "in 2 hr."; for a late or missed run, that run's time and the next one. */
export function NextDue({
  check,
  now,
}: {
  check: Pick<Check, "status" | "downReason" | "nextDueAt" | "nextRunAt">;
  now: number;
}) {
  const view = dueView(check, now);
  if (view.kind === "none") return <span className="text-muted-foreground">—</span>;
  if (view.kind === "upcoming") return <Time iso={view.at} now={now} />;
  const clock = formatClock(view.at, now);
  return (
    <span className="block">
      <Tooltip>
        <TooltipTrigger asChild>
          <time
            dateTime={view.at}
            className={cn(
              "block cursor-default truncate tabular-nums",
              view.missed ? "text-problem" : "text-warn",
            )}
          >
            {view.missed ? t.due.missed(clock) : t.due.late(clock)}
          </time>
        </TooltipTrigger>
        <TooltipContent>{formatExact(view.at)}</TooltipContent>
      </Tooltip>
      {view.next && (
        <span className="block truncate text-muted-foreground text-xs">
          {t.due.next} <Time iso={view.next} now={now} />
        </span>
      )}
    </span>
  );
}
