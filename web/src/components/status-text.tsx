import { type StatusView, statusView, type Tone } from "@/lib/status";
import type { Check } from "@/lib/types";
import { cn } from "@/lib/utils";

export const TONE_TEXT: Record<Tone, string> = {
  ok: "text-ok",
  problem: "text-problem",
  warn: "text-warn",
  muted: "text-muted-foreground",
};

const TONE_DOT: Record<Tone, string> = {
  ok: "bg-ok",
  problem: "bg-problem",
  warn: "bg-warn",
  muted: "bg-muted-foreground",
};

/** Status as coloured text with a small dot. No badge (Steelreed design system). */
export function StatusText({
  check,
  now,
  className,
}: {
  check: Pick<Check, "status" | "downReason" | "createdAt">;
  now?: number;
  className?: string;
}) {
  return <StatusLabel view={statusView(check, now)} className={className} />;
}

export function StatusLabel({ view, className }: { view: StatusView; className?: string }) {
  return (
    <span className={cn("inline-flex items-baseline gap-2", className)}>
      <span
        aria-hidden
        className={cn("size-2 shrink-0 translate-y-[-1px] rounded-full", TONE_DOT[view.tone])}
      />
      <span className={cn("font-medium uppercase tracking-wide text-xs", TONE_TEXT[view.tone])}>
        {view.label}
      </span>
      {view.reason && <span className="text-muted-foreground text-sm">{view.reason}</span>}
    </span>
  );
}
