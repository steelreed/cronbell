import { ChevronLeftIcon, ChevronRightIcon } from "lucide-react";
import { type Page, pageWindow } from "@/lib/checks-table";
import { t } from "@/lib/strings";
import { cn } from "@/lib/utils";

/** "1–25 of 40 checks" and a seven-slot pager; the pager hides when there is one page. */
export function Pagination<T>({ page, onPage }: { page: Page<T>; onPage: (page: number) => void }) {
  if (page.total === 0) return null;
  return (
    <div className="flex items-center justify-between gap-4 border-t px-4 py-3">
      <p className="text-muted-foreground text-sm tabular-nums">
        <span className="hidden sm:inline">{t.checks.range(page.from, page.to, page.total)}</span>
        <span className="sm:hidden">{t.checks.total(page.total)}</span>
      </p>
      {page.pages > 1 && (
        <nav aria-label={t.checks.pagination} className="flex items-center gap-1">
          <PagerButton
            label={t.checks.previous}
            disabled={page.page === 1}
            onClick={() => onPage(page.page - 1)}
          >
            <ChevronLeftIcon aria-hidden className="size-4" />
          </PagerButton>
          <span className="px-2 text-sm tabular-nums sm:hidden">
            {page.page} / {page.pages}
          </span>
          {pageWindow(page.page, page.pages).map((slot, i) =>
            slot === "gap" ? (
              <span
                // biome-ignore lint/suspicious/noArrayIndexKey: gaps are positions
                key={`gap-${i}`}
                aria-hidden
                className="hidden w-9 text-center text-muted-foreground sm:inline"
              >
                …
              </span>
            ) : (
              <button
                key={slot}
                type="button"
                onClick={() => onPage(slot)}
                aria-current={slot === page.page ? "page" : undefined}
                aria-label={t.checks.page(slot)}
                className={cn(
                  "hidden h-9 min-w-9 items-center justify-center rounded-lg border border-transparent px-2 font-medium text-sm tabular-nums transition-colors sm:inline-flex",
                  slot === page.page
                    ? "bg-secondary text-foreground"
                    : "text-foreground/70 hover:bg-accent",
                )}
              >
                {slot}
              </button>
            ),
          )}
          <PagerButton
            label={t.checks.next}
            disabled={page.page === page.pages}
            onClick={() => onPage(page.page + 1)}
          >
            <ChevronRightIcon aria-hidden className="size-4" />
          </PagerButton>
        </nav>
      )}
    </div>
  );
}

function PagerButton({
  label,
  disabled,
  onClick,
  children,
}: {
  label: string;
  disabled: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className="grid size-9 place-items-center rounded-lg text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent"
    >
      {children}
    </button>
  );
}
