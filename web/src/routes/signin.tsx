import { useMutation, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, redirect, useNavigate } from "@tanstack/react-router";
import type { FormEvent } from "react";
import { AuthLayout } from "@/components/auth-layout";
import { FormField } from "@/components/form-field";
import { ErrorNote } from "@/components/page";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ApiError, api } from "@/lib/api";
import { authRedirect, safeRedirect } from "@/lib/auth";
import { sessionQuery, setupQuery } from "@/lib/queries";
import { t } from "@/lib/strings";
import type { Session } from "@/lib/types";

export const Route = createFileRoute("/signin")({
  validateSearch: (search: Record<string, unknown>): { redirect?: string } =>
    typeof search.redirect === "string" ? { redirect: search.redirect } : {},
  beforeLoad: async ({ context }) => {
    const [setup, session] = await Promise.all([
      context.queryClient.fetchQuery(setupQuery),
      context.queryClient.fetchQuery(sessionQuery),
    ]);
    const target = authRedirect("signin", setup.required, session);
    if (target) throw redirect({ to: target });
  },
  component: SignInPage,
});

export function signInMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return t.signIn.wrong;
    if (error.status === 429) return t.signIn.wait(error.retryAfter ?? 30);
    return error.message;
  }
  return t.common.error;
}

function SignInPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const search = Route.useSearch();
  const signIn = useMutation({
    mutationFn: (credentials: { email: string; password: string }) =>
      api<Session>("/session", { method: "POST", body: credentials }),
    onSuccess: async (session) => {
      queryClient.setQueryData(sessionQuery.queryKey, session);
      await navigate({ href: safeRedirect(search.redirect) });
    },
  });
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    signIn.mutate({ email: String(data.get("email")), password: String(data.get("password")) });
  };
  return (
    <AuthLayout title={t.signIn.title}>
      <form className="space-y-4" onSubmit={submit}>
        <FormField id="email" label={t.signIn.email}>
          <Input id="email" name="email" type="email" autoComplete="username" required autoFocus />
        </FormField>
        <FormField id="password" label={t.signIn.password}>
          <Input
            id="password"
            name="password"
            type="password"
            autoComplete="current-password"
            required
          />
        </FormField>
        {signIn.error && <ErrorNote error={new Error(signInMessage(signIn.error))} />}
        <Button type="submit" size="lg" className="w-full" disabled={signIn.isPending}>
          {t.signIn.submit}
        </Button>
      </form>
    </AuthLayout>
  );
}
