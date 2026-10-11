import { createFileRoute, Outlet, redirect } from "@tanstack/react-router";
import { AppShell } from "@/components/app-shell";
import { authRedirect } from "@/lib/auth";
import { sessionQuery, setupQuery } from "@/lib/queries";

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
  const { session } = Route.useRouteContext();
  return (
    <AppShell session={session}>
      <Outlet />
    </AppShell>
  );
}
