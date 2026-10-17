import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Outlet, redirect } from "@tanstack/react-router";
import { AppShell } from "@/components/app-shell";
import { useSignOut } from "@/components/app-sidebar";
import { AuthLayout } from "@/components/auth-layout";
import { Button } from "@/components/ui/button";
import { authRedirect } from "@/lib/auth";
import { sessionQuery, setupQuery } from "@/lib/queries";
import { t } from "@/lib/strings";
import type { Session } from "@/lib/types";

/** Every dashboard page: needs a finished setup and a session. */
export const Route = createFileRoute("/_app")({
  beforeLoad: async ({ context, location }) => {
    const [setup, session] = await Promise.all([
      context.queryClient.fetchQuery(setupQuery),
      context.queryClient.fetchQuery(sessionQuery),
    ]);
    const target = authRedirect("app", setup.required, session);
    if (target === "/signin") {
      throw redirect({ to: "/signin", search: { redirect: location.href } });
    }
    if (target || !session) throw redirect({ to: target ?? "/signin" });
    return { session };
  },
  component: AppLayout,
});

function AppLayout() {
  const context = Route.useRouteContext();
  // The cached session follows project switches; the route's copy is the one it loaded with.
  const live = useQuery({ ...sessionQuery, initialData: context.session });
  const session = live.data ?? context.session;
  if (!session.project) return <NoProject session={session} />;
  return (
    <AppShell session={session}>
      <Outlet />
    </AppShell>
  );
}

/** An account removed from its last project: nothing to show but how to get back in. */
function NoProject({ session }: { session: Session }) {
  const signOut = useSignOut();
  return (
    <AuthLayout title={t.noProject.title} lead={t.noProject.lead}>
      <p className="text-muted-foreground text-sm">
        {t.account.signedInAs}{" "}
        <span className="font-medium text-foreground">{session.account.email}</span>
      </p>
      <Button variant="outline" size="lg" className="mt-6 w-full" onClick={signOut}>
        {t.account.signOut}
      </Button>
    </AuthLayout>
  );
}
