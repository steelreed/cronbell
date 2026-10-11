import type { ReactNode } from "react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { t } from "@/lib/strings";

export function PageHeader({
  title,
  lead,
  actions,
  children,
}: {
  title: ReactNode;
  lead?: ReactNode;
  actions?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <div className="mb-6 flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
      <div className="min-w-0">
        <h1 className="font-semibold text-xl tracking-tight">{title}</h1>
        {lead && <p className="mt-1 text-muted-foreground text-sm">{lead}</p>}
        {children}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

export function LoadingRows({ rows = 4 }: { rows?: number }) {
  return (
    <div className="space-y-2" role="status" aria-busy="true" aria-label={t.common.loading}>
      {Array.from({ length: rows }, (_, i) => (
        // biome-ignore lint/suspicious/noArrayIndexKey: placeholders have no identity
        <Skeleton key={i} className="h-10 w-full" />
      ))}
    </div>
  );
}

export function ErrorNote({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const message = error instanceof Error ? error.message : t.common.error;
  return (
    <Alert variant="destructive" role="alert">
      <AlertDescription className="flex flex-wrap items-center justify-between gap-3">
        <span>{message}</span>
        {onRetry && (
          <Button variant="outline" size="sm" onClick={onRetry}>
            {t.common.retry}
          </Button>
        )}
      </AlertDescription>
    </Alert>
  );
}

/** A labelled value in a definition grid. */
export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd className="mt-0.5 truncate text-sm">{children}</dd>
    </div>
  );
}

/** Red inline confirmation, used instead of a modal for destructive actions. */
export function ConfirmBar({
  message,
  confirmLabel,
  pending,
  onConfirm,
  onCancel,
}: {
  message: string;
  confirmLabel: string;
  pending?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <div
      role="alertdialog"
      aria-label={message}
      className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-problem/40 px-3 py-2"
    >
      <p className="text-sm">{message}</p>
      <div className="flex gap-2">
        <Button variant="ghost" size="sm" onClick={onCancel}>
          {t.common.cancel}
        </Button>
        <Button
          variant="outline"
          size="sm"
          onClick={onConfirm}
          disabled={pending}
          className="border-problem/60 text-problem hover:border-problem hover:bg-transparent hover:text-problem dark:border-problem/60 dark:bg-transparent dark:hover:bg-transparent"
        >
          {confirmLabel}
        </Button>
      </div>
    </div>
  );
}
