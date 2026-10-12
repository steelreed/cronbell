import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "./api";
import { checkQuery, checksQuery } from "./queries";
import type { Check } from "./types";

/** Pause or resume, and delete, keeping the list and the detail queries in step. */
export function useCheckActions(check: Pick<Check, "slug" | "status">, onDeleted?: () => void) {
  const queryClient = useQueryClient();
  const path = `/checks/${encodeURIComponent(check.slug)}`;
  const toggle = useMutation({
    mutationFn: () =>
      api<Check>(`${path}/${check.status === "PAUSED" ? "resume" : "pause"}`, { method: "POST" }),
    onSuccess: (saved) => {
      queryClient.setQueryData(checkQuery(check.slug).queryKey, saved);
      queryClient.setQueryData(checksQuery.queryKey, (list: Check[] | undefined) =>
        list?.map((item) => (item.slug === saved.slug ? saved : item)),
      );
    },
  });
  const remove = useMutation({
    mutationFn: () => api<void>(path, { method: "DELETE" }),
    onSuccess: async () => {
      queryClient.removeQueries({ queryKey: ["checks", check.slug] });
      queryClient.setQueryData(checksQuery.queryKey, (list: Check[] | undefined) =>
        list?.filter((item) => item.slug !== check.slug),
      );
      onDeleted?.();
      await queryClient.invalidateQueries({ queryKey: checksQuery.queryKey });
    },
  });
  return { toggle, remove };
}
