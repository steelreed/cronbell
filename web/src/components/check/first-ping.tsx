import { CheckIcon, SendIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useTestPing } from "@/lib/check-actions";
import { t } from "@/lib/strings";
import type { Check } from "@/lib/types";
import { ErrorNote } from "../page";
import { StatusDot } from "../status-text";
import { Time } from "../time";
import { IntegratePanel } from "./integrate-panel";

/**
 * "Send pings" for a check that had no ping when its page opened: waiting for the first one,
 * with a test ping from the browser, then that it arrived and when the next run is due.
 */
export function FirstPingPanel({ check, now }: { check: Check; now: number }) {
  const testPing = useTestPing(check);
  const waiting = check.lastPingAt === null;
  return (
    <IntegratePanel check={check}>
      <div className="mb-4 space-y-2">
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
          <p role="status" className="flex min-h-9 min-w-0 items-center gap-2 text-sm">
            {waiting ? (
              <>
                <StatusDot tone="muted" className="animate-pulse motion-reduce:animate-none" />
                <span className="text-muted-foreground">{t.check.firstPing.waiting}</span>
              </>
            ) : (
              <>
                <CheckIcon aria-hidden className="size-4 shrink-0 text-ok" />
                <span className="font-medium text-ok">{t.check.firstPing.received}</span>
                <span className="text-muted-foreground">
                  <Time iso={check.lastPingAt} now={now} />
                  {check.nextRunAt && (
                    <>
                      {" · "}
                      {t.check.firstPing.next} <Time iso={check.nextRunAt} now={now} />
                    </>
                  )}
                </span>
              </>
            )}
          </p>
          {waiting && (
            <Button
              variant="outline"
              onClick={() => testPing.mutate()}
              disabled={testPing.isPending}
              aria-busy={testPing.isPending || undefined}
            >
              <SendIcon aria-hidden />
              {testPing.isPending ? t.check.firstPing.testing : t.check.firstPing.test}
            </Button>
          )}
        </div>
        {testPing.error && <ErrorNote error={testPing.error} />}
      </div>
    </IntegratePanel>
  );
}
