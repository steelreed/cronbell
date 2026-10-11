import type { QueryClient } from "@tanstack/react-query";
import { createRootRouteWithContext, Link, Outlet } from "@tanstack/react-router";
import { TooltipProvider } from "@/components/ui/tooltip";
import { t } from "@/lib/strings";

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  component: () => (
    <TooltipProvider delayDuration={300}>
      <Outlet />
    </TooltipProvider>
  ),
  notFoundComponent: () => (
    <div className="mx-auto max-w-md px-4 py-24 text-center">
      <p className="text-muted-foreground">{t.notFound}</p>
      <Link to="/" className="mt-4 inline-block text-sm underline underline-offset-4">
        {t.nav.checks}
      </Link>
    </div>
  ),
});
