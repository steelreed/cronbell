import { useMutation, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { CheckForm } from "@/components/check-form";
import { PageHeader } from "@/components/page";
import { ApiError, api } from "@/lib/api";
import { t } from "@/lib/strings";
import type { Check, CheckDefinition } from "@/lib/types";

export const Route = createFileRoute("/_app/checks/new")({
  component: NewCheckPage,
});

function NewCheckPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const create = useMutation({
    mutationFn: async ({ slug, definition }: { slug: string; definition: CheckDefinition }) => {
      // PUT creates or updates: refuse to overwrite an existing check from the "new" form.
      const exists = await api<Check>(`/checks/${encodeURIComponent(slug)}`).then(
        () => true,
        (error) => {
          if (error instanceof ApiError && error.status === 404) return false;
          throw error;
        },
      );
      if (exists) throw new Error(t.form.errors.exists(slug));
      return api<Check>(`/checks/${encodeURIComponent(slug)}`, { method: "PUT", body: definition });
    },
    onSuccess: async (check) => {
      await queryClient.invalidateQueries({ queryKey: ["checks"] });
      await navigate({ to: "/checks/$slug", params: { slug: check.slug } });
    },
  });
  return (
    <>
      <PageHeader title={t.form.newTitle} />
      <CheckForm
        pending={create.isPending}
        error={create.error}
        onSubmit={(slug, definition) => create.mutate({ slug, definition })}
        onCancel={() => navigate({ to: "/checks" })}
      />
    </>
  );
}
