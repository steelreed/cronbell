import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { type FormEvent, useState } from "react";
import { AuthLayout } from "@/components/auth-layout";
import { FormField } from "@/components/form-field";
import { Bar, ErrorNote } from "@/components/page";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ApiError, api } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { inviteOfferQuery, sessionQuery, useSessionChanged } from "@/lib/queries";
import { t } from "@/lib/strings";
import type { InviteOffer, Session } from "@/lib/types";

/**
 * An invite link (DESIGN.md §6, Projects and invites): public. Shows who invites to what,
 * then creates the account, or accepts with the signed-in one.
 */
export const Route = createFileRoute("/invite/$token")({
  component: InvitePage,
});

interface AcceptBody {
  email?: string;
  password?: string;
  projectName?: string;
}

function InvitePage() {
  const { token } = Route.useParams();
  const offer = useQuery(inviteOfferQuery(token));
  const session = useQuery(sessionQuery);
  if (offer.isPending || session.isPending) {
    return (
      <AuthLayout title={t.common.loading}>
        <div aria-busy="true" className="space-y-3">
          <Bar className="w-3/4" />
          <Bar className="w-1/2" />
        </div>
      </AuthLayout>
    );
  }
  if (offer.isError) return <InvalidInvite error={offer.error} />;
  return <Offer token={token} offer={offer.data} session={session.data ?? null} />;
}

function InvalidInvite({ error }: { error: unknown }) {
  const tooMany = error instanceof ApiError && error.status === 429;
  const notFound = error instanceof ApiError && error.status === 404;
  return (
    <AuthLayout
      title={t.join.invalidTitle}
      lead={
        tooMany
          ? t.signIn.wait((error as ApiError).retryAfter ?? 30)
          : notFound
            ? t.join.invalidLead
            : t.common.loadError
      }
    >
      <Button asChild variant="outline" size="lg" className="w-full">
        <Link to="/signin">{t.join.goToSignIn}</Link>
      </Button>
    </AuthLayout>
  );
}

function Offer({
  token,
  offer,
  session,
}: {
  token: string;
  offer: InviteOffer;
  session: Session | null;
}) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const sessionChanged = useSessionChanged();
  const accept = useMutation({
    mutationFn: (body: AcceptBody) =>
      api<Session>(`/invites/${encodeURIComponent(token)}/accept`, { method: "POST", body }),
    onSuccess: async (next) => {
      sessionChanged(next);
      queryClient.removeQueries({ queryKey: ["invite-offer", token] });
      await navigate({ to: "/" });
    },
    onError: (error) => {
      // Used or withdrawn meanwhile: show the invalid-link page.
      if (error instanceof ApiError && error.status === 404) {
        queryClient.invalidateQueries({ queryKey: ["invite-offer", token] });
      }
    },
  });
  const project = offer.kind === "PROJECT";
  const title = project ? t.join.titleProject : t.join.titleMember(offer.projectName ?? "");
  const lead = project
    ? t.join.leadProject(offer.invitedBy)
    : t.join.leadMember(offer.invitedBy, offer.projectName ?? "");
  return (
    <AuthLayout title={title} lead={lead}>
      <p className="-mt-2 mb-6 text-muted-foreground text-sm">
        {t.join.validUntil(formatDate(offer.expiresAt))}
      </p>
      {session ? (
        <AcceptSignedIn
          session={session}
          project={project}
          pending={accept.isPending}
          error={accept.error}
          onAccept={(body) => accept.mutate(body)}
        />
      ) : (
        <AcceptNew
          token={token}
          project={project}
          pending={accept.isPending}
          error={accept.error}
          onAccept={(body) => accept.mutate(body)}
        />
      )}
    </AuthLayout>
  );
}

function ProjectNameField({ email }: { email: string }) {
  return (
    <FormField id="projectName" label={t.join.projectName} hint={t.join.projectNameHint}>
      <Input
        id="projectName"
        name="projectName"
        maxLength={200}
        placeholder={t.join.projectNamePlaceholder(email)}
        aria-describedby="projectName-hint"
      />
    </FormField>
  );
}

function AcceptSignedIn({
  session,
  project,
  pending,
  error,
  onAccept,
}: {
  session: Session;
  project: boolean;
  pending: boolean;
  error: unknown;
  onAccept: (body: AcceptBody) => void;
}) {
  const queryClient = useQueryClient();
  const email = session.account.email;
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const name = String(new FormData(event.currentTarget).get("projectName") ?? "").trim();
    onAccept(name ? { projectName: name } : {});
  };
  const useOther = async () => {
    try {
      await api("/session", { method: "DELETE" });
    } finally {
      queryClient.setQueryData(sessionQuery.queryKey, null);
    }
  };
  return (
    <form className="space-y-4" onSubmit={submit}>
      {project && <ProjectNameField email={email} />}
      {Boolean(error) && <ErrorNote error={error} />}
      <Button
        type="submit"
        size="lg"
        className="w-full"
        disabled={pending}
        aria-busy={pending || undefined}
      >
        <span className="truncate">{t.join.acceptAs(email)}</span>
      </Button>
      <Button type="button" variant="ghost" size="lg" className="w-full" onClick={useOther}>
        {t.join.otherAccount}
      </Button>
    </form>
  );
}

function AcceptNew({
  token,
  project,
  pending,
  error,
  onAccept,
}: {
  token: string;
  project: boolean;
  pending: boolean;
  error: unknown;
  onAccept: (body: AcceptBody) => void;
}) {
  const [mismatch, setMismatch] = useState(false);
  const [email, setEmail] = useState("");
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const password = String(data.get("password"));
    if (password !== String(data.get("confirm"))) {
      setMismatch(true);
      return;
    }
    setMismatch(false);
    const name = String(data.get("projectName") ?? "").trim();
    onAccept({
      email: String(data.get("email")),
      password,
      ...(name ? { projectName: name } : {}),
    });
  };
  return (
    <form className="space-y-4" onSubmit={submit}>
      <FormField id="email" label={t.setup.email}>
        <Input
          id="email"
          name="email"
          type="email"
          autoComplete="username"
          required
          autoFocus
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
      </FormField>
      <FormField id="password" label={t.setup.password} hint={t.setup.passwordHint}>
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
      {project && <ProjectNameField email={email} />}
      {Boolean(error) && <ErrorNote error={error} />}
      <Button
        type="submit"
        size="lg"
        className="mt-2 w-full"
        disabled={pending}
        aria-busy={pending || undefined}
      >
        {t.join.submit}
      </Button>
      <p className="text-center text-muted-foreground text-sm">
        {t.join.haveAccount}{" "}
        <Link
          to="/signin"
          search={{ redirect: `/invite/${token}` }}
          className="font-medium text-foreground underline-offset-4 hover:underline"
        >
          {t.join.signInFirst}
        </Link>
      </p>
    </form>
  );
}
