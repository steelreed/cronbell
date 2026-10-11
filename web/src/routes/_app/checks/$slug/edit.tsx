import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { CheckForm } from "@/components/check-form";
import { ErrorNote, LoadingRows, PageHeader } from "@/components/page";
import { api } from "@/lib/api";
import { checkQuery } from "@/lib/queries";
import { t } from "@/lib/strings";
import type { Check, CheckDefinition } from "@/lib/types";

export const Route = createFileRoute("/_app/checks/$slug/edit")({
  component: EditCheckPage,
});

function EditCheckPage() {
  const { slug } = Route.useParams();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const check = useQuery({ ...checkQuery(slug), refetchInterval: false });
  const save = useMutation({
    mutationFn: (definition: CheckDefinition) =>
      api<Check>(`/checks/${encodeURIComponent(slug)}`, { method: "PUT", body: definition }),
    onSuccess: async (saved) => {
      queryClient.setQueryData(checkQuery(slug).queryKey, saved);
      await queryClient.invalidateQueries({ queryKey: ["checks"] });
      await navigate({ to: "/checks/$slug", params: { slug } });
    },
  });
  return (
    <>
      <nav className="mb-2 text-sm">
        <Link
          to="/checks/$slug"
          params={{ slug }}
          className="text-muted-foreground hover:text-foreground"
        >
          ← {slug}
        </Link>
      </nav>
      <PageHeader title={t.form.editTitle(slug)} />
      {check.isPending ? (
        <LoadingRows rows={6} />
      ) : check.isError ? (
        <ErrorNote error={check.error} onRetry={() => check.refetch()} />
      ) : (
        <CheckForm
          check={check.data}
          pending={save.isPending}
          error={save.error}
          onSubmit={(_, definition) => save.mutate(definition)}
          onCancel={() => navigate({ to: "/checks/$slug", params: { slug } })}
        />
      )}
    </>
  );
}
