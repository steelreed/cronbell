import { useMutation } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { LogOutIcon } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { useSignOut } from "@/components/app-sidebar";
import { DescriptionList } from "@/components/description-list";
import { FormField } from "@/components/form-field";
import { ErrorNote, Panel } from "@/components/page";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ApiError, api } from "@/lib/api";
import { useSession } from "@/lib/queries";
import { t } from "@/lib/strings";

export const Route = createFileRoute("/_app/account")({
  component: AccountPage,
});

/** The signed-in account: who it is, its password, sign out. */
function AccountPage() {
  const session = useSession();
  const signOut = useSignOut();
  if (!session) return null;
  const items = [
    { label: t.accountPage.email, value: session.account.email },
    ...(session.project
      ? [
          {
            label: t.accountPage.role,
            value: `${t.project.roles[session.project.role]} · ${session.project.name}`,
          },
        ]
      : []),
    ...(session.account.instanceAdmin
      ? [{ label: t.accountPage.instanceAdmin, value: t.accountPage.instanceAdminHint }]
      : []),
  ];
  return (
    <div className="grid max-w-3xl gap-6">
      <p className="text-muted-foreground text-sm">{t.accountPage.lead}</p>
      <Panel title={t.accountPage.details}>
        <DescriptionList items={items} />
      </Panel>
      <Panel title={t.accountPage.passwordTitle} lead={t.accountPage.passwordLead}>
        <ChangePassword />
      </Panel>
      <Panel title={t.accountPage.signOutTitle} lead={t.accountPage.signOutLead}>
        <Button variant="outline" onClick={signOut}>
          <LogOutIcon aria-hidden />
          {t.account.signOut}
        </Button>
      </Panel>
    </div>
  );
}

function passwordMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 429) {
    return t.signIn.wait(error.retryAfter ?? 30);
  }
  return error instanceof Error ? error.message : t.common.error;
}

function ChangePassword() {
  const id = useId();
  const [mismatch, setMismatch] = useState(false);
  const change = useMutation({
    mutationFn: (body: { currentPassword: string; newPassword: string }) =>
      api<void>("/account/password", { method: "POST", body }),
  });
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const newPassword = String(data.get("next"));
    if (newPassword !== String(data.get("repeat"))) {
      setMismatch(true);
      return;
    }
    setMismatch(false);
    change.mutate(
      { currentPassword: String(data.get("current")), newPassword },
      { onSuccess: () => form.reset() },
    );
  };
  return (
    <form onSubmit={submit} className="grid max-w-sm gap-4">
      <FormField id={`${id}-current`} label={t.accountPage.current}>
        <Input
          id={`${id}-current`}
          name="current"
          type="password"
          autoComplete="current-password"
          required
        />
      </FormField>
      <FormField id={`${id}-next`} label={t.accountPage.next} hint={t.setup.passwordHint}>
        <Input
          id={`${id}-next`}
          name="next"
          type="password"
          autoComplete="new-password"
          minLength={8}
          maxLength={128}
          required
        />
      </FormField>
      <FormField
        id={`${id}-repeat`}
        label={t.accountPage.repeat}
        error={mismatch ? t.setup.mismatch : undefined}
      >
        <Input
          id={`${id}-repeat`}
          name="repeat"
          type="password"
          autoComplete="new-password"
          required
          aria-invalid={mismatch || undefined}
        />
      </FormField>
      {change.error && <ErrorNote error={new Error(passwordMessage(change.error))} />}
      {change.isSuccess && (
        <p role="status" className="text-ok text-sm">
          {t.accountPage.changed}
        </p>
      )}
      <Button
        type="submit"
        className="justify-self-start"
        disabled={change.isPending}
        aria-busy={change.isPending || undefined}
      >
        {t.accountPage.submit}
      </Button>
    </form>
  );
}
