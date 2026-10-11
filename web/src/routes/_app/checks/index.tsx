import { createFileRoute, redirect } from "@tanstack/react-router";

/** The checks list lives at "/"; "/checks" is where people (and alert links) guess it is. */
export const Route = createFileRoute("/_app/checks/")({
  beforeLoad: () => {
    throw redirect({ to: "/" });
  },
});
