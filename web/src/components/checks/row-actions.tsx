import { Link } from "@tanstack/react-router";
import {
  ArrowUpRightIcon,
  CopyIcon,
  EllipsisIcon,
  PauseIcon,
  PencilIcon,
  PlayIcon,
  Trash2Icon,
} from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useCheckActions } from "@/lib/check-actions";
import { t } from "@/lib/strings";
import type { Check } from "@/lib/types";
import { ConfirmDialog } from "../confirm-dialog";
import { copyText } from "../copy-button";

/** The ⋯ menu of a check: open, pause or resume, copy the ping URL, delete (confirmed). */
export function CheckActionsMenu({
  check,
  onDeleted,
  showOpen = true,
  showEdit = false,
  triggerClassName,
  triggerVariant = "ghost",
}: {
  check: Check;
  onDeleted?: () => void;
  showOpen?: boolean;
  showEdit?: boolean;
  triggerClassName?: string;
  triggerVariant?: "ghost" | "outline";
}) {
  const { toggle, remove } = useCheckActions(check, onDeleted);
  const [confirming, setConfirming] = useState(false);
  const paused = check.status === "PAUSED";
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant={triggerVariant}
            size={triggerVariant === "outline" ? "icon" : "icon-sm"}
            aria-label={`${t.common.actions}: ${check.name}`}
            className={triggerClassName}
            onClick={(event) => event.stopPropagation()}
          >
            <EllipsisIcon aria-hidden />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          align="end"
          className="w-auto min-w-48"
          onClick={(event) => event.stopPropagation()}
        >
          {showOpen && (
            <DropdownMenuItem asChild>
              <Link to="/checks/$slug" params={{ slug: check.slug }}>
                <ArrowUpRightIcon aria-hidden />
                {t.common.open}
              </Link>
            </DropdownMenuItem>
          )}
          {showEdit && (
            <DropdownMenuItem asChild>
              <Link to="/checks/$slug/edit" params={{ slug: check.slug }}>
                <PencilIcon aria-hidden />
                {t.common.edit}
              </Link>
            </DropdownMenuItem>
          )}
          <DropdownMenuItem onSelect={() => toggle.mutate()} disabled={toggle.isPending}>
            {paused ? <PlayIcon aria-hidden /> : <PauseIcon aria-hidden />}
            {paused ? t.check.resume : t.check.pause}
          </DropdownMenuItem>
          <DropdownMenuItem onSelect={() => void copyText(check.pingUrl)}>
            <CopyIcon aria-hidden />
            {t.checks.copyPingUrl}
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem variant="destructive" onSelect={() => setConfirming(true)}>
            <Trash2Icon aria-hidden />
            {t.common.delete}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={t.check.deleteTitle}
        description={
          <>
            <span className="font-medium font-mono text-foreground">{check.slug}</span>{" "}
            {t.check.deleteRest}
          </>
        }
        confirmLabel={t.check.deleteButton}
        pending={remove.isPending}
        error={remove.error}
        onConfirm={() => remove.mutate(undefined, { onSuccess: () => setConfirming(false) })}
      />
    </>
  );
}
