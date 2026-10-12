import { Link } from "@tanstack/react-router";
import { PlusIcon } from "lucide-react";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { firstCheckSnippets } from "@/lib/snippets";
import { t } from "@/lib/strings";
import type { Session } from "@/lib/types";
import { browserTimeZone } from "@/lib/validation";
import { CodeBlock } from "./copy-button";

function Step({
  n,
  title,
  lead,
  children,
}: {
  n: number;
  title: string;
  lead: string;
  children?: ReactNode;
}) {
  return (
    <li className="grid grid-cols-[1.75rem_minmax(0,1fr)] gap-x-3 px-4 py-5 sm:px-5">
      <span
        aria-hidden
        className="grid size-7 place-items-center rounded-full border font-medium text-muted-foreground text-xs tabular-nums"
      >
        {n}
      </span>
      <div className="min-w-0">
        <h3 className="pt-1 font-medium text-sm">{title}</h3>
        <p className="mt-1 max-w-[65ch] text-muted-foreground text-sm text-pretty">{lead}</p>
        {children && <div className="mt-3">{children}</div>}
      </div>
    </li>
  );
}

/** First use: what a check is, then the three steps with a ready curl to create and ping one. */
export function EmptyChecks({ session }: { session: Session }) {
  const snippets = firstCheckSnippets(
    window.location.origin,
    session.pingBaseUrl,
    browserTimeZone(),
  );
  return (
    <section className="rounded-xl border bg-card">
      <div className="px-4 pt-5 sm:px-5">
        <h2 className="font-semibold text-base">{t.checks.empty.title}</h2>
        <p className="mt-1 max-w-[65ch] text-muted-foreground text-sm text-pretty">
          {t.checks.empty.lead}
        </p>
      </div>
      <ol className="mt-2 divide-y">
        <Step n={1} title={t.checks.empty.step1} lead={t.checks.empty.step1Lead}>
          <div className="flex flex-col gap-2 sm:flex-row">
            <Button asChild>
              <Link to="/checks/new">
                <PlusIcon aria-hidden />
                {t.checks.empty.form}
              </Link>
            </Button>
            <Button variant="outline" asChild>
              <Link to="/keys">{t.checks.empty.key}</Link>
            </Button>
          </div>
          <div className="mt-3">
            <CodeBlock code={snippets.create} />
          </div>
        </Step>
        <Step n={2} title={t.checks.empty.step2} lead={t.checks.empty.step2Lead}>
          <CodeBlock code={snippets.ping} />
        </Step>
        <Step n={3} title={t.checks.empty.step3} lead={t.checks.empty.step3Lead}>
          <Button variant="outline" asChild>
            <Link to="/channels">{t.checks.empty.channel}</Link>
          </Button>
        </Step>
      </ol>
    </section>
  );
}
