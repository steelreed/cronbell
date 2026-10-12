import { CheckIcon, ChevronsUpDownIcon } from "lucide-react";
import { useDeferredValue, useMemo, useState } from "react";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { t } from "@/lib/strings";
import { cn } from "@/lib/utils";
import { allZones, COMMON_ZONES, offsetText, zoneMatches, zoneOffset } from "@/lib/zones";

/**
 * Time zone combobox, as on steelreed.com's cron tool: common zones first, then every zone the
 * browser knows, each with its current UTC offset. Search matches ids, cities, aliases
 * (Saigon, HCM) and offsets ("+7").
 */
export function ZonePicker({
  id,
  value,
  onChange,
  invalid,
  describedBy,
}: {
  id: string;
  value: string;
  onChange: (zone: string) => void;
  invalid?: boolean;
  describedBy?: string;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const search = useDeferredValue(query);
  const [now, setNow] = useState(() => Date.now());
  const groups = useMemo(() => {
    if (!open) return [];
    const zones = allZones();
    const matching = search.trim() ? zones.filter((zone) => zoneMatches(zone, search, now)) : zones;
    const common = search.trim() ? [] : COMMON_ZONES.filter((zone) => zones.includes(zone));
    return [
      { id: "common", label: t.form.commonZones, zones: common },
      { id: "all", label: t.form.otherZones, zones: matching },
    ].filter((group) => group.zones.length > 0);
  }, [open, search, now]);
  const pick = (zone: string) => {
    onChange(zone);
    setOpen(false);
    setQuery("");
  };
  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        if (next) setNow(Date.now());
        else setQuery("");
        setOpen(next);
      }}
    >
      <PopoverTrigger asChild>
        <button
          id={id}
          type="button"
          role="combobox"
          aria-expanded={open}
          aria-haspopup="listbox"
          aria-invalid={invalid || undefined}
          aria-describedby={describedBy}
          className="flex h-10 w-full items-center gap-2 whitespace-nowrap rounded-lg border border-input bg-card px-3 text-left text-base outline-none transition-colors focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 aria-expanded:border-ring aria-invalid:border-destructive md:text-sm dark:bg-white/[0.03]"
        >
          <span className={cn("min-w-0 flex-1 truncate", !value && "text-muted-foreground")}>
            {value || t.form.timeZone}
          </span>
          {value && (
            <span className="font-mono text-muted-foreground text-xs tabular-nums">
              {offsetText(zoneOffset(value, now))}
            </span>
          )}
          <ChevronsUpDownIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
        </button>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        className="w-[min(var(--radix-popover-trigger-width),calc(100vw-2rem))] min-w-[min(22rem,calc(100vw-2rem))] p-0"
      >
        <Command shouldFilter={false} loop>
          <CommandInput
            value={query}
            onValueChange={setQuery}
            placeholder={t.form.timeZoneSearch}
            aria-label={t.form.timeZoneSearch}
          />
          <CommandList className="max-h-[min(20rem,calc(var(--radix-popover-content-available-height)-3.5rem))]">
            <CommandEmpty>{t.form.timeZoneEmpty}</CommandEmpty>
            {groups.map((group) => (
              <CommandGroup key={group.id} heading={group.label}>
                {group.zones.map((zone) => (
                  <CommandItem
                    key={`${group.id}:${zone}`}
                    value={`${group.id}:${zone}`}
                    onSelect={() => pick(zone)}
                  >
                    <span
                      className={cn("min-w-0 flex-1 truncate", zone === value && "font-medium")}
                    >
                      {zone}
                    </span>
                    <span className="font-mono text-muted-foreground text-xs tabular-nums">
                      {offsetText(zoneOffset(zone, now))}
                    </span>
                    <CheckIcon
                      aria-hidden
                      className={cn("size-4", zone === value ? "opacity-100" : "opacity-0")}
                    />
                  </CommandItem>
                ))}
              </CommandGroup>
            ))}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}
