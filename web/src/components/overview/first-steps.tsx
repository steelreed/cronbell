import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { CheckIcon } from "lucide-react";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import {
  type FirstStep,
  type FirstStepId,
  firstSteps,
  setUp,
  waitingCheck,
} from "@/lib/first-steps";
import { channelsQuery, membersQuery } from "@/lib/queries";
import { t } from "@/lib/strings";
import type { Channel, Check, Session } from "@/lib/types";
import { cn } from "@/lib/utils";
import { FirstCheckStart } from "../empty-checks";
import { Panel } from "../page";

const TITLES: Record<FirstStepId, string> = {
  create: t.firstSteps.create,
  ping: t.firstSteps.ping,
  channel: t.firstSteps.channel,
  invite: t.firstSteps.invite,
};

/**
 * The overview of a young project: what is left to set up, one step open at a time. It is the
 * whole page while there is no check, and goes away once a check has pinged and a channel has
 * delivered; inviting a teammate is optional.
 */
export function FirstSteps({ checks, session }: { checks: Check[]; session: Session }) {
  const owner = session.project?.role === "OWNER";
  const channels = useQuery(channelsQuery);
  const members = useQuery({ ...membersQuery, enabled: owner });
  const empty = checks.length === 0;
  // With checks, wait for the channels rather than flash a list that is done already.
  if (!empty && !channels.isSuccess) return null;
  const steps = firstSteps(checks, channels.data ?? [], owner ? (members.data ?? []) : null);
  if (!empty && setUp(steps)) return null;
  const current = steps.find((step) => !step.done && !step.optional)?.id;
  const required = steps.filter((step) => !step.optional);
  return (
    <Panel
      title={t.firstSteps.title}
      lead={empty ? t.checks.empty.lead : undefined}
      action={
        <span className="text-muted-foreground text-sm tabular-nums">
          {t.firstSteps.progress(required.filter((step) => step.done).length, required.length)}
        </span>
      }
      flush
    >
      <ol className="divide-y border-t">
        {steps.map((step, i) => (
          <StepRow key={step.id} n={i + 1} step={step} open={step.id === current}>
            {step.id === current || (step.optional && !step.done) ? (
              <StepBody
                id={step.id}
                checks={checks}
                channels={channels.data ?? []}
                session={session}
              />
            ) : null}
          </StepRow>
        ))}
      </ol>
    </Panel>
  );
}

function StepRow({
  n,
  step,
  open,
  children,
}: {
  n: number;
  step: FirstStep;
  open: boolean;
  children: ReactNode;
}) {
  return (
    <li
      aria-current={open ? "step" : undefined}
      className={cn(
        "grid grid-cols-[1.5rem_minmax(0,1fr)] gap-x-3 px-4 sm:px-5",
        open ? "py-4" : "py-3",
      )}
    >
      {step.done ? (
        <CheckIcon aria-hidden className="mt-0.5 size-4 justify-self-center text-ok" />
      ) : (
        <span
          aria-hidden
          className={cn(
            "grid size-6 place-items-center rounded-full border text-xs tabular-nums",
            open ? "border-foreground/40 font-medium" : "text-muted-foreground",
          )}
        >
          {n}
        </span>
      )}
      <div className="min-w-0">
        <h3
          className={cn(
            "min-h-6 pt-0.5 text-sm",
            step.done ? "text-muted-foreground" : open && "font-medium",
          )}
        >
          {step.done && <span className="sr-only">{t.firstSteps.done} </span>}
          {TITLES[step.id]}
          {step.optional && (
            <span className="text-muted-foreground"> · {t.firstSteps.optional}</span>
          )}
        </h3>
        {children && <div className="mt-2">{children}</div>}
      </div>
    </li>
  );
}

function StepBody({
  id,
  checks,
  channels,
  session,
}: {
  id: FirstStepId;
  checks: Check[];
  channels: Channel[];
  session: Session;
}) {
  switch (id) {
    case "create":
      return <FirstCheckStart session={session} />;
    case "ping": {
      const check = waitingCheck(checks);
      return (
        <Lead text={t.firstSteps.pingLead}>
          {check && (
            <Button variant="outline" asChild>
              <Link to="/checks/$slug" params={{ slug: check.slug }}>
                {t.firstSteps.openCheck(check.name)}
              </Link>
            </Button>
          )}
        </Lead>
      );
    }
    case "channel":
      return (
        <Lead text={channels.length > 0 ? t.firstSteps.channelTestLead : t.firstSteps.channelLead}>
          <Button variant="outline" asChild>
            <Link to="/channels">
              {channels.length > 0 ? t.firstSteps.testChannel : t.firstSteps.addChannel}
            </Link>
          </Button>
        </Lead>
      );
    default:
      return (
        <Link to="/members" className="font-medium text-sm underline-offset-4 hover:underline">
          {t.firstSteps.inviteAction}
        </Link>
      );
  }
}

function Lead({ text, children }: { text: string; children?: ReactNode }) {
  return (
    <div className="space-y-3">
      <p className="max-w-[65ch] text-muted-foreground text-sm text-pretty">{text}</p>
      {children}
    </div>
  );
}
