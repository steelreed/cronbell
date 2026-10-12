import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { CheckForm } from "@/components/check-form";
import { Bar, ErrorState, PageHeader, Panel } from "@/components/page";
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
      <PageHeader title={t.form.editTitle(check.data?.name ?? slug)} />
      {check.isPending ? (
        <Panel className="max-w-4xl">
          <div aria-busy="true" className="space-y-6">
            {Array.from({ length: 5 }, (_, i) => (
              // biome-ignore lint/suspicious/noArrayIndexKey: placeholders have no identity
              <Bar key={i} className="h-4 w-2/3" />
            ))}
          </div>
        </Panel>
      ) : check.isError ? (
        <Panel className="max-w-4xl">
          <ErrorState error={check.error} onRetry={() => check.refetch()} />
        </Panel>
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
