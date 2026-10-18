import type { ReactNode } from "react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { crontabSnippet, kubernetesSnippet, shellSnippet, springSnippet } from "@/lib/snippets";
import { t } from "@/lib/strings";
import type { Check } from "@/lib/types";
import { CodeBlock, CopyButton } from "../copy-button";
import { Panel } from "../page";

/**
 * The ping URL with copy, and ready snippets for crontab, shell, Kubernetes and Spring; a
 * status line above the URL when given (the first ping).
 */
export function IntegratePanel({ check, children }: { check: Check; children?: ReactNode }) {
  return (
    <Panel title={t.check.integrate} lead={t.check.integrateLead}>
      {children}
      <div className="flex min-w-0 items-center gap-2">
        <code
          className="min-w-0 flex-1 truncate rounded-lg border bg-page px-3 py-1.5 font-mono text-[13px]"
          title={check.pingUrl}
        >
          {check.pingUrl}
        </code>
        <CopyButton value={check.pingUrl} />
      </div>
      <Tabs defaultValue="crontab" className="mt-4">
        <div className="scrollbar-clean overflow-x-auto">
          <TabsList variant="line">
            <TabsTrigger value="crontab">{t.check.snippets.crontab}</TabsTrigger>
            <TabsTrigger value="shell">{t.check.snippets.shell}</TabsTrigger>
            <TabsTrigger value="kubernetes">{t.check.snippets.kubernetes}</TabsTrigger>
            <TabsTrigger value="spring">{t.check.snippets.spring}</TabsTrigger>
          </TabsList>
        </div>
        <TabsContent value="crontab">
          <CodeBlock code={crontabSnippet(check)} />
        </TabsContent>
        <TabsContent value="shell">
          <CodeBlock code={shellSnippet(check)} />
        </TabsContent>
        <TabsContent value="kubernetes">
          <CodeBlock code={kubernetesSnippet(check)} />
        </TabsContent>
        <TabsContent value="spring">
          <p className="mb-2 max-w-[65ch] text-muted-foreground text-sm text-pretty">
            {t.check.springNote}
          </p>
          <CodeBlock code={springSnippet(check)} />
        </TabsContent>
      </Tabs>
    </Panel>
  );
}
