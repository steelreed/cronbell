import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { MailPlusIcon } from "lucide-react";
import { useState } from "react";
import { InviteDialog } from "@/components/invite-dialog";
import { InvitesTable } from "@/components/invites-table";
import { EmptyLine, ErrorState, LoadingList } from "@/components/page";
import { Button } from "@/components/ui/button";
import { adminInvitesQuery, useSession } from "@/lib/queries";
import { t } from "@/lib/strings";
import { useNow } from "@/lib/use-now";

export const Route = createFileRoute("/_app/invites")({
  component: InvitesPage,
});

/**
 * Project invites, for the instance admin: each link gives one person an account and a project
 * of their own (DESIGN.md §6, Projects and invites).
 */
function InvitesPage() {
  const session = useSession();
  if (!session?.account.instanceAdmin) {
    return <p className="text-muted-foreground text-sm">{t.invites.adminOnly}</p>;
  }
  return <AdminInvites />;
}

function AdminInvites() {
  const invites = useQuery(adminInvitesQuery);
  const now = useNow();
  const [creating, setCreating] = useState(false);
  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <p className="max-w-[65ch] text-muted-foreground text-sm text-pretty">{t.invites.lead}</p>
        <Button onClick={() => setCreating(true)} className="shrink-0 self-start sm:self-auto">
          <MailPlusIcon aria-hidden />
          {t.invites.create}
        </Button>
      </div>
      <section className="overflow-hidden rounded-xl border bg-card">
        {invites.isPending ? (
          <LoadingList rows={2} />
        ) : invites.isError ? (
          <ErrorState error={invites.error} onRetry={() => invites.refetch()} />
        ) : invites.data.length === 0 ? (
          <EmptyLine>{t.invites.empty}</EmptyLine>
        ) : (
          <InvitesTable invites={invites.data} now={now} />
        )}
      </section>
      <InviteDialog kind="PROJECT" open={creating} onOpenChange={setCreating} />
    </div>
  );
}
