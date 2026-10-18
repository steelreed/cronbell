import type { QueryClient } from "@tanstack/react-query";
import { createRootRouteWithContext, Outlet } from "@tanstack/react-router";
import { NotFound } from "@/components/not-found";
import { ScrollbarAutohide } from "@/components/scrollbar-autohide";
import { TooltipProvider } from "@/components/ui/tooltip";

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  component: () => (
    <TooltipProvider delayDuration={300}>
      <ScrollbarAutohide />
      <Outlet />
    </TooltipProvider>
  ),
  notFoundComponent: () => <NotFound />,
});
