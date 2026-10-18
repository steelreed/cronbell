import { Link } from "@tanstack/react-router";
import { ChevronDownIcon, PlusIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { firstCheckSnippets } from "@/lib/snippets";
import { t } from "@/lib/strings";
import type { Session } from "@/lib/types";
import { browserTimeZone } from "@/lib/validation";
import { CodeBlock } from "./copy-button";
import { Panel } from "./page";

const STARTER_URL = "https://github.com/steelreed/cronbell-spring-boot-starter";

/**
 * How to make the first check: the form first, which lands on the check's page with its ping
 * URL; the API and the Spring Boot starter behind a disclosure for those who script it.
 */
export function FirstCheckStart({ session }: { session: Session }) {
  const snippets = firstCheckSnippets(
    window.location.origin,
    session.pingBaseUrl,
    browserTimeZone(),
  );
  return (
    <div className="space-y-4">
      <Button asChild>
        <Link to="/checks/new">
          <PlusIcon aria-hidden />
          {t.checks.empty.create}
        </Link>
      </Button>
      <details className="group">
        <summary className="inline-flex cursor-pointer list-none items-center gap-1.5 text-muted-foreground text-sm underline-offset-4 hover:text-foreground hover:underline [&::-webkit-details-marker]:hidden">
          {t.checks.empty.other}
          <ChevronDownIcon
            aria-hidden
            className="size-4 transition-transform group-open:rotate-180 motion-reduce:transition-none"
          />
        </summary>
        <div className="mt-3 max-w-3xl space-y-3 text-muted-foreground text-sm text-pretty">
          <p>{t.checks.empty.apiLead}</p>
          <CodeBlock code={snippets.create} />
          <CodeBlock code={snippets.ping} />
          <p className="pt-1">{t.checks.empty.springLead}</p>
          <CodeBlock code={snippets.spring} />
          <div className="flex flex-wrap gap-2 pt-1">
            {session.project?.role === "OWNER" && (
              <Button variant="outline" asChild>
                <Link to="/keys">{t.checks.empty.key}</Link>
              </Button>
            )}
            <Button variant="ghost" asChild>
              <a href={STARTER_URL} target="_blank" rel="noreferrer">
                {t.checks.empty.starter}
              </a>
            </Button>
          </div>
        </div>
      </details>
    </div>
  );
}

/** A project without checks: what a check is, and how to make the first. */
export function EmptyChecks({ session }: { session: Session }) {
  return (
    <Panel title={t.checks.empty.title} lead={t.checks.empty.lead}>
      <FirstCheckStart session={session} />
    </Panel>
  );
}
