import { Link } from "@tanstack/react-router";
import type { StatusFilter } from "@/lib/checks-table";
import type { Health, Tone } from "@/lib/status";
import { t } from "@/lib/strings";
import { cn } from "@/lib/utils";
import { StatusDot } from "../status-text";

interface Tile {
  label: string;
  value: number;
  meta: string;
  filter: StatusFilter;
  tone: Tone | null;
}

/**
 * Four counts in one divided block. The numbers stay in the text colour; a coloured dot next
 * to the label says the count is a problem, and only when it is not zero.
 */
export function StatTiles({ counts }: { counts: Record<Health, number> }) {
  const total = Object.values(counts).reduce((sum, n) => sum + n, 0);
  const tiles: Tile[] = [
    {
      label: t.overview.tiles.total,
      value: total,
      meta: t.overview.totalMeta(counts.paused, counts.new),
      filter: "all",
      tone: null,
    },
    {
      label: t.overview.tiles.down,
      value: counts.down,
      meta: t.overview.downMeta(counts.down),
      filter: "down",
      tone: counts.down > 0 ? "problem" : null,
    },
    {
      label: t.overview.tiles.late,
      value: counts.late,
      meta: t.overview.lateMeta(counts.late),
      filter: "late",
      tone: counts.late > 0 ? "warn" : null,
    },
    {
      label: t.overview.tiles.up,
      value: counts.up,
      meta: t.overview.upMeta,
      filter: "up",
      tone: null,
    },
  ];
  return (
    <div className="grid grid-cols-2 gap-px overflow-hidden rounded-xl border bg-border lg:grid-cols-4">
      {tiles.map((tile) => (
        <Link
          key={tile.label}
          to="/checks"
          search={tile.filter === "all" ? {} : { status: tile.filter }}
          className="group min-w-0 bg-card p-4 transition-colors hover:bg-accent sm:p-5"
        >
          <span className="flex items-center gap-2 font-medium text-muted-foreground text-xs">
            {tile.tone && <StatusDot tone={tile.tone} />}
            {tile.label}
          </span>
          <span
            className={cn(
              "mt-1 block font-semibold text-2xl tabular-nums tracking-tight",
              tile.value === 0 && tile.filter !== "all" && "text-muted-foreground",
            )}
          >
            {tile.value.toLocaleString("en")}
          </span>
          <span className="mt-1 block truncate text-muted-foreground text-xs">{tile.meta}</span>
        </Link>
      ))}
    </div>
  );
}
