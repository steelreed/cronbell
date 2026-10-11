import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createRouter, RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { ApiError } from "./lib/api";
import { sessionQuery } from "./lib/queries";
import { routeTree } from "./routeTree.gen";
import "./index.css";

/** A 401 on any dashboard call means the session ended: back to sign-in. */
function onError(error: unknown) {
  if (
    error instanceof ApiError &&
    error.status === 401 &&
    router.state.location.pathname !== "/signin"
  ) {
    queryClient.setQueryData(sessionQuery.queryKey, null);
    void router.navigate({ to: "/signin", search: { redirect: router.state.location.href } });
  }
}

const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError }),
  mutationCache: new MutationCache({ onError }),
  defaultOptions: {
    queries: {
      retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
    },
  },
});

const router = createRouter({
  routeTree,
  context: { queryClient },
  defaultPreload: "intent",
  defaultPreloadStaleTime: 0,
});

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}

const root = document.getElementById("root");
if (!root) throw new Error("#root is missing from index.html");
createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
