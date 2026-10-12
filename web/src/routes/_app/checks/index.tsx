import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { PlusIcon } from "lucide-react";
import { ChecksTable, ChecksTableSkeleton } from "@/components/checks/checks-table";
import { Pagination } from "@/components/checks/pagination";
import { SearchBox, SortMenu, StatusTabs } from "@/components/checks/toolbar";
import { EmptyChecks } from "@/components/empty-checks";
import { ErrorState } from "@/components/page";
import { Button } from "@/components/ui/button";
import {
  DEFAULT_DIR,
  filterChecks,
  paginate,
  SORT_KEYS,
  type SortDir,
  type SortKey,
  STATUS_FILTERS,
  type StatusFilter,
  sortChecks,
} from "@/lib/checks-table";
import { checksQuery, recentRunsQuery } from "@/lib/queries";
import { countByHealth } from "@/lib/status";
import { t } from "@/lib/strings";
import { useNow } from "@/lib/use-now";

export interface ChecksSearch {
  status?: Exclude<StatusFilter, "all">;
  q?: string;
  sort?: SortKey;
  dir?: SortDir;
  page?: number;
}

export const Route = createFileRoute("/_app/checks/")({
  validateSearch: (search: Record<string, unknown>): ChecksSearch => {
    const result: ChecksSearch = {};
    if (STATUS_FILTERS.includes(search.status as StatusFilter) && search.status !== "all") {
      result.status = search.status as ChecksSearch["status"];
    }
    if (typeof search.q === "string" && search.q) result.q = search.q;
    if (SORT_KEYS.includes(search.sort as SortKey)) result.sort = search.sort as SortKey;
    if (search.dir === "asc" || search.dir === "desc") result.dir = search.dir;
    const page = Number(search.page);
    if (Number.isInteger(page) && page > 1) result.page = page;
    return result;
  },
  component: ChecksPage,
});

/**
 * Every check in one table: search, a status filter with counts, sortable columns, problems
 * first by default. Filters, sort and page live in the URL, so a view can be shared.
 */
function ChecksPage() {
  const { session } = Route.useRouteContext();
  const search = Route.useSearch();
  const navigate = useNavigate({ from: Route.fullPath });
  const checks = useQuery(checksQuery);
  const runs = useQuery(recentRunsQuery);
  const now = useNow();

  const status: StatusFilter = search.status ?? "all";
  const sort = search.sort ?? "status";
  const dir = search.dir ?? DEFAULT_DIR[sort];
  const query = search.q ?? "";
  const update = (next: Partial<ChecksSearch>) =>
    navigate({ search: (prev) => ({ ...prev, page: undefined, ...next }), replace: true });

  const add = (
    <Button asChild>
      <Link to="/checks/new">
        <PlusIcon aria-hidden />
        {t.checks.add}
      </Link>
    </Button>
  );

  if (checks.isSuccess && checks.data.length === 0) return <EmptyChecks session={session} />;

  const counts = checks.data ? countByHealth(checks.data, now) : null;
  const visible = checks.data
    ? sortChecks(filterChecks(checks.data, query, status, now), sort, dir, now)
    : [];
  const page = paginate(visible, search.page ?? 1);

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 lg:flex-row lg:items-center">
        <SearchBox
          value={query}
          onChange={(q) => update({ q: q || undefined })}
          className="lg:w-72"
        />
        {counts && (
          <StatusTabs
            value={status}
            counts={counts}
            total={checks.data?.length ?? 0}
            onChange={(next) => update({ status: next === "all" ? undefined : next })}
          />
        )}
        <div className="flex items-center gap-2 lg:ml-auto">
          <SortMenu
            value={sort}
            onChange={(key) => update({ sort: key === "status" ? undefined : key, dir: undefined })}
          />
          <div className="ml-auto">{add}</div>
        </div>
      </div>
      <section className="overflow-hidden rounded-xl border bg-card">
        {checks.isPending ? (
          <ChecksTableSkeleton />
        ) : checks.isError ? (
          <ErrorState error={checks.error} onRetry={() => checks.refetch()} />
        ) : visible.length === 0 ? (
          <p className="px-4 py-10 text-center text-muted-foreground text-sm">
            {query
              ? t.checks.noneMatchSearch(query.length > 24 ? `${query.slice(0, 24)}…` : query)
              : t.checks.noneMatch(t.checks.filters[status].toLowerCase())}{" "}
            <button
              type="button"
              className="font-medium text-foreground underline-offset-4 hover:underline"
              onClick={() => update({ q: undefined, status: undefined })}
            >
              {query ? t.checks.clearSearch : t.checks.clearFilters}
            </button>
          </p>
        ) : (
          <>
            <ChecksTable
              checks={page.items}
              runs={runs.data}
              now={now}
              sort={{
                sort,
                dir,
                onSort: (key) =>
                  update({
                    sort: key === "status" && key !== sort ? undefined : key,
                    dir: key === sort ? (dir === "asc" ? "desc" : "asc") : undefined,
                  }),
              }}
            />
            <Pagination page={page} onPage={(n) => update({ page: n > 1 ? n : undefined })} />
          </>
        )}
      </section>
    </div>
  );
}
