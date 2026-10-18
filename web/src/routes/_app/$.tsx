import { createFileRoute } from "@tanstack/react-router";
import { NotFound } from "@/components/not-found";

/** Any other path: a 404 inside the app, behind the same sign-in as every page. */
export const Route = createFileRoute("/_app/$")({
  component: () => <NotFound />,
});
