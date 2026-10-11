import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { formatExact, formatRelative } from "@/lib/format";

/** Relative time; the exact time on hover. */
export function Time({ iso, now }: { iso: string | null; now: number }) {
  if (!iso) return <span className="text-muted-foreground">—</span>;
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <time dateTime={iso} className="cursor-default whitespace-nowrap tabular-nums">
          {formatRelative(iso, now)}
        </time>
      </TooltipTrigger>
      <TooltipContent>{formatExact(iso)}</TooltipContent>
    </Tooltip>
  );
}
