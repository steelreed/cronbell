import type { ReactNode } from "react";
import { ThemeToggle } from "./app-shell";
import { CronbellMark } from "./logo";

/** Narrow centred page for setup and sign-in. */
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
    <div className="flex min-h-dvh flex-col">
      <div className="flex justify-end p-3">
        <ThemeToggle />
      </div>
      <main className="mx-auto flex w-full max-w-sm flex-1 flex-col justify-center px-4 pb-24">
        <CronbellMark size={32} />
        <h1 className="mt-5 font-semibold text-2xl tracking-tight">{title}</h1>
        {lead && <p className="mt-2 text-muted-foreground text-sm">{lead}</p>}
        <div className="mt-8">{children}</div>
      </main>
    </div>
  );
}
