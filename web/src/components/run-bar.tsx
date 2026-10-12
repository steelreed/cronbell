import { formatDuration, formatExact } from "@/lib/format";
import { countTicks, type Tick, type TickKind } from "@/lib/runs";
import { t } from "@/lib/strings";
import { cn } from "@/lib/utils";

const TICK_CLASS: Record<TickKind, string> = {
  ok: "bg-ok",
  fail: "bg-problem",
  missed: "border border-problem bg-transparent",
  empty: "bg-foreground/10",
};

function tickTitle(tick: Tick): string {
  if (tick.kind === "empty" || !tick.at) return t.runs.empty;
  const parts = [formatExact(tick.at), t.runs[tick.kind]];
  if (tick.exitCode !== null && tick.exitCode !== 0) parts.push(t.runs.exit(tick.exitCode));
  if (tick.durationMs !== null) parts.push(formatDuration(tick.durationMs));
  return parts.join(" · ");
}

/**
 * Recent runs as a row of ticks, oldest on the left: green success, red failure, a red
 * outline for the run the check is DOWN for having missed, grey where there is no history.
 */
export function RunBar({
  ticks,
  size = "sm",
  className,
}: {
  ticks: Tick[];
  size?: "sm" | "lg";
  className?: string;
}) {
  const counts = countTicks(ticks);
  const summary =
    counts.ok + counts.fail + counts.missed === 0
      ? t.runs.none
      : t.runs.summary(counts.ok, counts.fail + counts.missed);
  return (
    <div
      role="img"
      aria-label={summary}
      className={cn("flex items-end", size === "sm" ? "h-5 gap-0.5" : "h-9 gap-[3px]", className)}
    >
      {ticks.map((tick, i) => (
        <span
          // biome-ignore lint/suspicious/noArrayIndexKey: ticks are positions in a fixed-size bar
          key={i}
          title={tickTitle(tick)}
          data-kind={tick.kind}
          className={cn(
            "block shrink-0 rounded-full",
            size === "sm" ? "h-full w-1" : "h-full min-w-1 flex-1",
            TICK_CLASS[tick.kind],
          )}
        />
      ))}
    </div>
  );
}
