import { useMutation, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, redirect, useNavigate } from "@tanstack/react-router";
import { type FormEvent, useState } from "react";
import { AuthLayout } from "@/components/auth-layout";
import { FormField } from "@/components/form-field";
import { ErrorNote } from "@/components/page";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { api } from "@/lib/api";
import { authRedirect } from "@/lib/auth";
import { sessionQuery, setupQuery } from "@/lib/queries";
import { t } from "@/lib/strings";
import type { Session } from "@/lib/types";

export const Route = createFileRoute("/setup")({
  beforeLoad: async ({ context }) => {
    const [setup, session] = await Promise.all([
      context.queryClient.fetchQuery(setupQuery),
      context.queryClient.fetchQuery(sessionQuery),
    ]);
    const target = authRedirect("setup", setup.required, session);
    if (target) throw redirect({ to: target });
  },
  component: SetupPage,
});

function SetupPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [mismatch, setMismatch] = useState(false);
  const setUp = useMutation({
    mutationFn: (credentials: { email: string; password: string }) =>
      api<Session>("/setup", { method: "POST", body: credentials }),
    onSuccess: async (session) => {
      queryClient.setQueryData(setupQuery.queryKey, { required: false });
      queryClient.setQueryData(sessionQuery.queryKey, session);
      await navigate({ to: "/" });
    },
  });
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const password = String(data.get("password"));
    if (password !== String(data.get("confirm"))) {
      setMismatch(true);
      return;
    }
    setMismatch(false);
    setUp.mutate({ email: String(data.get("email")), password });
  };
  return (
    <AuthLayout title={t.setup.title} lead={t.setup.lead}>
      <form className="space-y-4" onSubmit={submit} noValidate={false}>
        <FormField id="email" label={t.setup.email}>
          <Input id="email" name="email" type="email" autoComplete="username" required autoFocus />
        </FormField>
        <FormField id="password" label={t.setup.password} hint="8 to 128 characters.">
          <Input
            id="password"
            name="password"
            type="password"
            autoComplete="new-password"
            minLength={8}
            maxLength={128}
            required
          />
        </FormField>
        <FormField
          id="confirm"
          label={t.setup.confirm}
          error={mismatch ? t.setup.mismatch : undefined}
        >
          <Input
            id="confirm"
            name="confirm"
            type="password"
            autoComplete="new-password"
            required
            aria-invalid={mismatch || undefined}
          />
        </FormField>
        {setUp.error && <ErrorNote error={setUp.error} />}
        <Button type="submit" size="lg" className="w-full" disabled={setUp.isPending}>
          {t.setup.submit}
        </Button>
      </form>
    </AuthLayout>
  );
}
