import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { t } from "@/lib/strings";
import { CronbellMark } from "./logo";
import { ThemeIcon, ThemeMenu } from "./theme-menu";

/** Setup and sign-in: a centred card with the product mark, on the app's chrome colour. */
export function AuthLayout({
  title,
  lead,
  children,
}: {
  title: string;
  lead?: string;
  children: ReactNode;
}) {
  return (
    <div className="flex min-h-dvh flex-col bg-page">
      <div className="flex justify-end p-3">
        <ThemeMenu
          trigger={(theme) => (
            <Button variant="ghost" size="icon" aria-label={t.theme.label}>
              <ThemeIcon theme={theme} />
            </Button>
          )}
        />
      </div>
      <main className="flex flex-1 flex-col items-center justify-center px-4 pb-24">
        <div className="w-full max-w-sm rounded-xl border bg-card p-6 sm:p-8">
          <div className="flex items-center gap-2 font-semibold tracking-tight">
            <CronbellMark size={24} />
            {t.product}
          </div>
          <h1 className="mt-6 font-semibold text-xl">{title}</h1>
          {lead && <p className="mt-1.5 text-muted-foreground text-sm text-pretty">{lead}</p>}
          <div className="mt-6">{children}</div>
        </div>
      </main>
    </div>
  );
}
