import { Link } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import { t } from "@/lib/strings";

/** Every "not found": a page, a check. Inside the shell when signed in, with a way back. */
export function NotFound({
  title = t.notFound.title,
  lead = t.notFound.lead,
  back = "/",
}: {
  title?: string;
  lead?: string;
  back?: "/" | "/checks";
}) {
  return (
    <div className="mx-auto max-w-md px-4 pt-16 pb-16 text-center sm:pt-24">
      <p className="font-medium font-mono text-muted-foreground text-sm">404</p>
      <h1 className="mt-2 font-semibold text-xl text-balance">{title}</h1>
      <p className="mt-2 text-muted-foreground text-sm text-pretty">{lead}</p>
      <Button asChild size="lg" className="mt-6">
        <Link to={back}>{back === "/" ? t.notFound.toOverview : t.notFound.toChecks}</Link>
      </Button>
    </div>
  );
}

export function CheckNotFound({ slug }: { slug: string }) {
  return (
    <NotFound title={t.notFound.checkTitle} lead={t.notFound.checkLead(slug)} back="/checks" />
  );
}
