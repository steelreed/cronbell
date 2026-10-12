import { RotateCwIcon } from "lucide-react";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { t } from "@/lib/strings";
import { cn } from "@/lib/utils";

/** The header of a page that has its own title (detail and form pages). */
export function PageHeader({
  title,
  meta,
  actions,
}: {
  title: ReactNode;
  meta?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <header className="mb-5 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
      <div className="min-w-0 flex-1">
        <h1 className="break-words font-semibold text-xl text-balance">{title}</h1>
        {meta && <div className="mt-1.5 text-muted-foreground text-sm">{meta}</div>}
      </div>
      {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
    </header>
  );
}

/** A hairline frame: the one container for cards, tables and lists. */
export function Panel({
  title,
  lead,
  action,
  flush,
  className,
  children,
}: {
  title?: ReactNode;
  lead?: ReactNode;
  action?: ReactNode;
  /** Body without side padding: tables and divided lists run edge to edge. */
  flush?: boolean;
  className?: string;
  children: ReactNode;
}) {
  return (
    <section
      className={cn(
        "flex min-w-0 flex-col rounded-xl border bg-card text-card-foreground",
        className,
      )}
    >
      {title && (
        <div className="flex items-start justify-between gap-3 px-4 pt-4 sm:px-5">
          <div className="min-w-0">
            <h2 className="font-semibold text-base">{title}</h2>
            {lead && <p className="mt-0.5 text-muted-foreground text-sm text-pretty">{lead}</p>}
          </div>
          {action && <div className="shrink-0">{action}</div>}
        </div>
      )}
      <div
        className={cn(
          "min-h-0 flex-1",
          !flush && "px-4 pb-4 sm:px-5 sm:pb-5",
          title && (flush ? "mt-3" : "mt-4"),
          !title && !flush && "pt-4 sm:pt-5",
        )}
      >
        {children}
      </div>
    </section>
  );
}

/** Empty list: one muted line, no illustration. */
export function EmptyLine({ children }: { children: ReactNode }) {
  return <p className="px-4 py-8 text-center text-muted-foreground text-sm">{children}</p>;
}

/** A failed load: what failed, why, and a retry. */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const message = error instanceof Error ? error.message : null;
  return (
    <div role="alert" className="flex flex-col items-center gap-3 px-4 py-10 text-center">
      <div>
        <p className="font-medium text-problem text-sm">{t.common.loadError}</p>
        {message && <p className="mt-1 text-muted-foreground text-sm">{message}</p>}
      </div>
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry}>
          <RotateCwIcon aria-hidden />
          {t.common.retry}
        </Button>
      )}
    </div>
  );
}

/** An error after an action (save, delete): a single line under the control. */
export function ErrorNote({ error }: { error: unknown }) {
  const message = error instanceof Error ? error.message : t.common.error;
  return (
    <p role="alert" className="text-problem text-sm">
      {message}
    </p>
  );
}

/** A grey bar of a loading skeleton. */
export function Bar({ className }: { className?: string }) {
  return (
    <span
      aria-hidden
      className={cn(
        "block h-3 animate-pulse rounded-full bg-foreground/[0.07] motion-reduce:animate-none",
        className,
      )}
    />
  );
}

/** Skeleton of a divided list: rows shaped like the real ones. */
export function LoadingList({ rows = 4 }: { rows?: number }) {
  return (
    <ul aria-busy="true" className="divide-y">
      <li className="sr-only" role="status">
        {t.common.loading}
      </li>
      {Array.from({ length: rows }, (_, i) => (
        // biome-ignore lint/suspicious/noArrayIndexKey: placeholders have no identity
        <li key={i} className="flex items-center gap-4 px-4 py-4 sm:px-5">
          <div className="min-w-0 flex-1 space-y-2">
            <Bar className={i % 2 ? "w-1/4" : "w-1/3"} />
            <Bar className="w-1/5" />
          </div>
          <Bar className="w-16" />
        </li>
      ))}
    </ul>
  );
}
