import { CheckIcon, ChevronDownIcon, SearchIcon, XIcon } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { SORT_KEYS, type SortKey, STATUS_FILTERS, type StatusFilter } from "@/lib/checks-table";
import type { Health } from "@/lib/status";
import { t } from "@/lib/strings";
import { cn } from "@/lib/utils";
import { StatusDot } from "../status-text";

const FILTER_TONE: Partial<Record<StatusFilter, "problem" | "warn" | "ok">> = {
  down: "problem",
  late: "warn",
};

export function SearchBox({
  value,
  onChange,
  className,
}: {
  value: string;
  onChange: (value: string) => void;
  className?: string;
}) {
  const input = useRef<HTMLInputElement>(null);
  return (
    <div className={cn("relative", className)}>
      <SearchIcon
        aria-hidden
        className="pointer-events-none absolute inset-y-0 left-3 my-auto size-4 text-muted-foreground"
      />
      <input
        ref={input}
        type="search"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={t.checks.search}
        aria-label={t.checks.search}
        spellCheck={false}
        className="h-9 w-full rounded-lg border border-input bg-card pr-9 pl-9 text-base outline-none transition-colors placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm dark:bg-white/[0.03] [&::-webkit-search-cancel-button]:appearance-none"
      />
      {value && (
        <button
          type="button"
          aria-label={t.checks.clearSearch}
          onClick={() => {
            onChange("");
            input.current?.focus();
          }}
          className="absolute inset-y-0 right-1 my-auto grid size-7 place-items-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
        >
          <XIcon aria-hidden className="size-4" />
        </button>
      )}
    </div>
  );
}

/** Whether a horizontal scroller has more to show past its start and its end. */
function useOverflow<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [overflow, setOverflow] = useState({ start: false, end: false });
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    const update = () =>
      setOverflow({
        start: element.scrollLeft > 0,
        end: element.scrollLeft + element.clientWidth < element.scrollWidth - 1,
      });
    update();
    element.addEventListener("scroll", update, { passive: true });
    const observer = new ResizeObserver(update);
    observer.observe(element);
    return () => {
      element.removeEventListener("scroll", update);
      observer.disconnect();
    };
  }, []);
  return [ref, overflow] as const;
}

const FADE = "2rem";

/**
 * Exactly one status at a time, with counts: a row of tabs that scrolls on narrow screens.
 * The edges fade where more tabs are hidden, and the chosen one is scrolled into view.
 */
export function StatusTabs({
  value,
  counts,
  total,
  onChange,
}: {
  value: StatusFilter;
  counts: Record<Health, number>;
  total: number;
  onChange: (value: StatusFilter) => void;
}) {
  const [scroller, overflow] = useOverflow<HTMLDivElement>();
  useEffect(() => {
    scroller.current
      ?.querySelector(`[data-filter="${value}"]`)
      ?.scrollIntoView({ block: "nearest", inline: "nearest" });
  }, [scroller, value]);
  const mask = `linear-gradient(to right, ${overflow.start ? `transparent, black ${FADE}` : "black"}, ${
    overflow.end ? `black calc(100% - ${FADE}), transparent` : "black"
  })`;
  return (
    <div
      ref={scroller}
      className="scrollbar-clean min-w-0 overflow-x-auto"
      style={{ maskImage: mask }}
    >
      <div
        role="radiogroup"
        aria-label={t.checks.statusFilter}
        className="flex w-max items-center gap-1"
      >
        {STATUS_FILTERS.map((filter) => {
          const count = filter === "all" ? total : counts[filter];
          const selected = value === filter;
          const tone = FILTER_TONE[filter];
          return (
            // biome-ignore lint/a11y/useSemanticElements: a styled radio group of buttons
            <button
              key={filter}
              type="button"
              role="radio"
              aria-checked={selected}
              data-filter={filter}
              onClick={() => onChange(filter)}
              className={cn(
                "flex h-9 shrink-0 items-center gap-2 rounded-lg border border-transparent px-3 font-medium text-sm transition-colors",
                selected
                  ? "bg-secondary text-foreground"
                  : "text-foreground/70 hover:bg-accent hover:text-foreground",
              )}
            >
              {tone && count > 0 && <StatusDot tone={tone} />}
              {t.checks.filters[filter]}
              <span className="font-normal text-foreground/60 text-xs tabular-nums">{count}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

/** Below md the table becomes a list without headers: sort from a menu instead. */
export function SortMenu({
  value,
  onChange,
}: {
  value: SortKey;
  onChange: (key: SortKey) => void;
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" className="md:hidden">
          <span className="text-muted-foreground">{t.checks.sortBy}:</span>
          <span className="max-w-40 truncate">{t.checks.sorts[value]}</span>
          <ChevronDownIcon aria-hidden className="text-muted-foreground" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-auto min-w-56">
        {SORT_KEYS.map((key) => (
          <DropdownMenuItem key={key} onSelect={() => onChange(key)}>
            {t.checks.sorts[key]}
            {key === value && <CheckIcon aria-hidden className="ml-auto" />}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
