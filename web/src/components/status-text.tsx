import { type StatusView, statusView, type Tone } from "@/lib/status";
import type { Check } from "@/lib/types";
import { cn } from "@/lib/utils";

export const TONE_TEXT: Record<Tone, string> = {
  ok: "text-ok",
  problem: "text-problem",
  warn: "text-warn",
  muted: "text-muted-foreground",
};

export const TONE_DOT: Record<Tone, string> = {
  ok: "bg-ok",
  problem: "bg-problem",
  warn: "bg-warn",
  muted: "bg-muted-foreground",
};

/** Status as coloured text with a small dot. No badge (Steelreed design system). */
export function StatusText({
  check,
  now,
  showReason = true,
  className,
}: {
  check: Pick<Check, "status" | "downReason" | "createdAt"> & Partial<Pick<Check, "nextDueAt">>;
  now?: number;
  showReason?: boolean;
  className?: string;
}) {
  const view = statusView(check, now);
  return <StatusLabel view={showReason ? view : { ...view, reason: null }} className={className} />;
}

export function StatusDot({ tone, className }: { tone: Tone; className?: string }) {
  return (
    <span
      aria-hidden
      className={cn("inline-block size-2 shrink-0 rounded-full", TONE_DOT[tone], className)}
    />
  );
}

export function StatusLabel({ view, className }: { view: StatusView; className?: string }) {
  return (
    <span className={cn("inline-flex min-w-0 items-center gap-2", className)}>
      <StatusDot tone={view.tone} />
      <span
        className={cn("shrink-0 font-medium text-xs uppercase tracking-wide", TONE_TEXT[view.tone])}
      >
        {view.label}
      </span>
      {view.reason && <span className="truncate text-muted-foreground text-sm">{view.reason}</span>}
    </span>
  );
}
