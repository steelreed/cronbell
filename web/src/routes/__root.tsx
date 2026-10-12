import type { QueryClient } from "@tanstack/react-query";
import { createRootRouteWithContext, Link, Outlet } from "@tanstack/react-router";
import { ScrollbarAutohide } from "@/components/scrollbar-autohide";
import { Button } from "@/components/ui/button";
import { TooltipProvider } from "@/components/ui/tooltip";
import { t } from "@/lib/strings";

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  component: () => (
    <TooltipProvider delayDuration={300}>
      <ScrollbarAutohide />
      <Outlet />
    </TooltipProvider>
  ),
  notFoundComponent: () => (
    <div className="mx-auto max-w-md px-4 pt-24 pb-16 text-center">
      <p className="font-medium text-muted-foreground text-sm">404</p>
      <h1 className="mt-2 font-semibold text-xl">{t.notFound}</h1>
      <Button asChild size="lg" className="mt-6">
        <Link to="/">{t.goToOverview}</Link>
      </Button>
    </div>
  ),
});
