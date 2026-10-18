import { useQuery } from "@tanstack/react-query";
import { type FormEvent, type ReactNode, useEffect, useId, useState } from "react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { formatRelative, formatRun } from "@/lib/format";
import { channelsQuery, nextRunsQuery } from "@/lib/queries";
import { t } from "@/lib/strings";
import type { Check, CheckDefinition } from "@/lib/types";
import {
  browserTimeZone,
  type CheckFormErrors,
  type CheckFormValues,
  isCronLike,
  isValidTimeZone,
  slugify,
  splitSeconds,
  toDefinition,
  type Unit,
  validateCheckForm,
} from "@/lib/validation";
import { canonicalZone } from "@/lib/zones";
import { ChannelIcon } from "./channel-icon";
import { FormField } from "./form-field";
import { ErrorNote } from "./page";
import { ZonePicker } from "./zone-picker";

export function initialValues(check?: Check): CheckFormValues {
  if (!check) {
    return {
      slug: "",
      name: "",
      kind: "CRON",
      cron: "0 3 * * *",
      period: "1",
      periodUnit: "h",
      timeZone: browserTimeZone(),
      grace: "30",
      graceUnit: "m",
      maxRuntime: "",
      maxRuntimeUnit: "m",
      alertTo: "all",
      channels: [],
    };
  }
  const period = splitSeconds(check.periodSeconds ?? 3600);
  const grace = splitSeconds(check.graceSeconds);
  const maxRuntime = splitSeconds(check.maxRuntimeSeconds);
  return {
    slug: check.slug,
    name: check.name === check.slug ? "" : check.name,
    kind: check.scheduleKind,
    cron: check.cron ?? "",
    period: period.value,
    periodUnit: period.unit,
    timeZone: canonicalZone(check.timeZone),
    grace: grace.value || "0",
    graceUnit: grace.unit,
    maxRuntime: maxRuntime.value,
    maxRuntimeUnit: maxRuntime.unit,
    alertTo: check.channels ? "picked" : "all",
    channels: check.channels ?? [],
  };
}

function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return debounced;
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <fieldset className="grid gap-4 px-4 py-5 sm:px-6 lg:grid-cols-[11rem_minmax(0,1fr)] lg:gap-8">
      <legend className="sr-only">{title}</legend>
      <p aria-hidden className="font-semibold text-sm">
        {title}
      </p>
      <div className="min-w-0 space-y-5">{children}</div>
    </fieldset>
  );
}

/** Create or edit a check. The slug is fixed once the check exists. */
export function CheckForm({
  check,
  pending,
  error,
  onSubmit,
  onCancel,
}: {
  check?: Check;
  pending: boolean;
  error: unknown;
  onSubmit: (slug: string, definition: CheckDefinition) => void;
  onCancel: () => void;
}) {
  const id = useId();
  const [values, setValues] = useState<CheckFormValues>(() => initialValues(check));
  const [errors, setErrors] = useState<CheckFormErrors>({});
  const [slugEdited, setSlugEdited] = useState(false);
  const set = <K extends keyof CheckFormValues>(key: K, value: CheckFormValues[K]) =>
    setValues((current) => ({ ...current, [key]: value }));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validateCheckForm(values);
    setErrors(found);
    const first = Object.keys(found)[0];
    if (first) {
      document.getElementById(`${id}-${first}`)?.focus();
      return;
    }
    onSubmit(values.slug, toDefinition(values));
  };

  const field = (key: keyof CheckFormValues) => ({
    id: `${id}-${key}`,
    "aria-invalid": errors[key] ? true : undefined,
    "aria-describedby": errors[key] ? `${id}-${key}-error` : `${id}-${key}-hint`,
  });

  return (
    <form onSubmit={submit} noValidate className="max-w-4xl rounded-xl border bg-card">
      <div className="divide-y">
        <Section title={t.form.sections.identity}>
          <FormField
            id={`${id}-name`}
            label={t.form.name}
            hint={t.form.nameHint}
            error={errors.name}
          >
            <Input
              {...field("name")}
              value={values.name}
              onChange={(event) => {
                const name = event.target.value;
                setValues((current) => ({
                  ...current,
                  name,
                  // The slug follows the name until someone edits it.
                  slug: check || slugEdited ? current.slug : slugify(name),
                }));
              }}
              autoFocus={!check}
            />
          </FormField>
          <FormField
            id={`${id}-slug`}
            label={t.form.slug}
            required
            hint={check ? t.form.slugFixedHint : t.form.slugHint}
            error={errors.slug}
          >
            <Input
              {...field("slug")}
              value={values.slug}
              onChange={(event) => {
                setSlugEdited(true);
                set("slug", event.target.value);
              }}
              disabled={Boolean(check)}
              required
              aria-required="true"
              className="font-mono"
              spellCheck={false}
              autoCapitalize="off"
            />
          </FormField>
        </Section>

        <Section title={t.form.sections.schedule}>
          <div className="space-y-2">
            <span id={`${id}-kind-label`} className="block font-medium text-sm">
              {t.form.scheduleKind}
            </span>
            <ToggleGroup
              type="single"
              variant="outline"
              aria-labelledby={`${id}-kind-label`}
              value={values.kind}
              onValueChange={(kind) => kind && set("kind", kind as CheckFormValues["kind"])}
            >
              <ToggleGroupItem value="CRON" className="px-4">
                {t.form.cron}
              </ToggleGroupItem>
              <ToggleGroupItem value="PERIOD" className="px-4">
                {t.form.period}
              </ToggleGroupItem>
            </ToggleGroup>
          </div>
          <div className="grid gap-5 md:grid-cols-2">
            {values.kind === "CRON" ? (
              <FormField
                id={`${id}-cron`}
                label={t.form.cron}
                hint={t.form.cronHint}
                error={errors.cron}
              >
                <Input
                  {...field("cron")}
                  value={values.cron}
                  onChange={(event) => set("cron", event.target.value)}
                  className="font-mono"
                  spellCheck={false}
                  autoCapitalize="off"
                />
              </FormField>
            ) : (
              <FormField
                id={`${id}-period`}
                label={t.form.period}
                hint={t.form.periodNote}
                error={errors.period}
              >
                <DurationInput
                  {...field("period")}
                  value={values.period}
                  unit={values.periodUnit}
                  units={["m", "h", "d"]}
                  onValue={(value) => set("period", value)}
                  onUnit={(unit) => set("periodUnit", unit)}
                />
              </FormField>
            )}
            <FormField id={`${id}-timeZone`} label={t.form.timeZone} error={errors.timeZone}>
              <ZonePicker
                id={`${id}-timeZone`}
                value={values.timeZone}
                onChange={(zone) => set("timeZone", zone)}
                invalid={Boolean(errors.timeZone)}
                describedBy={errors.timeZone ? `${id}-timeZone-error` : undefined}
              />
            </FormField>
          </div>
          {values.kind === "CRON" && <NextRuns cron={values.cron} timeZone={values.timeZone} />}
        </Section>

        <Section title={t.form.sections.alerts}>
          <div className="grid gap-5 md:grid-cols-2">
            <FormField
              id={`${id}-grace`}
              label={t.form.grace}
              hint={t.form.graceHint}
              error={errors.grace}
            >
              <DurationInput
                {...field("grace")}
                value={values.grace}
                unit={values.graceUnit}
                units={["s", "m", "h", "d"]}
                onValue={(value) => set("grace", value)}
                onUnit={(unit) => set("graceUnit", unit)}
              />
            </FormField>
            <FormField
              id={`${id}-maxRuntime`}
              label={t.form.maxRuntime}
              hint={t.form.maxRuntimeHint}
              error={errors.maxRuntime}
            >
              <DurationInput
                {...field("maxRuntime")}
                value={values.maxRuntime}
                unit={values.maxRuntimeUnit}
                units={["s", "m", "h", "d"]}
                onValue={(value) => set("maxRuntime", value)}
                onUnit={(unit) => set("maxRuntimeUnit", unit)}
              />
            </FormField>
          </div>
        </Section>

        <Section title={t.form.sections.channels}>
          <ChannelChoice
            id={id}
            alertTo={values.alertTo}
            picked={values.channels}
            error={errors.channels}
            onAlertTo={(alertTo) => set("alertTo", alertTo)}
            onPicked={(channels) => set("channels", channels)}
          />
        </Section>
      </div>

      <div className="flex flex-col-reverse gap-3 border-t px-4 py-4 sm:flex-row sm:items-center sm:justify-end sm:px-6">
        {Boolean(error) && (
          <div className="sm:mr-auto">
            <ErrorNote error={error} />
          </div>
        )}
        <Button type="button" variant="ghost" size="lg" onClick={onCancel}>
          {t.common.cancel}
        </Button>
        <Button type="submit" size="lg" disabled={pending} aria-busy={pending || undefined}>
          {pending ? t.common.saving : check ? t.common.save : t.form.create}
        </Button>
      </div>
    </form>
  );
}

/** The next five runs of the cron schedule, computed by the server with the detector's code. */
function NextRuns({ cron, timeZone }: { cron: string; timeZone: string }) {
  const debouncedCron = useDebounced(cron.trim(), 400);
  const debouncedZone = useDebounced(timeZone, 400);
  const ready = isCronLike(debouncedCron) && isValidTimeZone(debouncedZone);
  const preview = useQuery({ ...nextRunsQuery(debouncedCron, debouncedZone), enabled: ready });
  if (!ready) return null;
  return (
    <div className="rounded-lg border bg-page px-4 py-3">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4">
        <p className="font-medium text-sm">{t.form.nextRuns}</p>
        <p className="text-muted-foreground text-xs">{t.form.nextRunsHint}</p>
      </div>
      {preview.isError ? (
        <p className="mt-2 text-problem text-sm" role="status">
          {preview.error instanceof Error ? preview.error.message : t.common.error}
        </p>
      ) : (
        <ol className="mt-2 space-y-1 text-sm" aria-busy={preview.isPending || undefined}>
          {(preview.data?.runs ?? Array.from({ length: 5 }, () => null)).map((run, i) => (
            // biome-ignore lint/suspicious/noArrayIndexKey: runs are positions in time
            <li key={i} className="flex justify-between gap-4 tabular-nums">
              {run ? (
                <>
                  <span>{formatRun(run)}</span>
                  <span className="text-muted-foreground">{formatRelative(run)}</span>
                </>
              ) : (
                <span className="my-1 block h-3 w-48 animate-pulse rounded-full bg-foreground/[0.07]" />
              )}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

/** Every channel of the project, or a ticked list of them. */
function ChannelChoice({
  id,
  alertTo,
  picked,
  error,
  onAlertTo,
  onPicked,
}: {
  id: string;
  alertTo: CheckFormValues["alertTo"];
  picked: string[];
  error?: string;
  onAlertTo: (alertTo: CheckFormValues["alertTo"]) => void;
  onPicked: (channels: string[]) => void;
}) {
  const channels = useQuery(channelsQuery);
  const none = channels.isSuccess && channels.data.length === 0;
  return (
    <div className="space-y-3">
      <span id={`${id}-alertTo-label`} className="block font-medium text-sm">
        {t.form.alertTo}
      </span>
      <ToggleGroup
        type="single"
        variant="outline"
        aria-labelledby={`${id}-alertTo-label`}
        value={alertTo}
        onValueChange={(next) => next && onAlertTo(next as CheckFormValues["alertTo"])}
      >
        <ToggleGroupItem value="all" className="px-4">
          {t.form.allChannels}
        </ToggleGroupItem>
        <ToggleGroupItem value="picked" className="px-4" disabled={none && alertTo === "all"}>
          {t.form.someChannels}
        </ToggleGroupItem>
      </ToggleGroup>
      <p className="text-muted-foreground text-xs">
        {none
          ? t.form.noChannels
          : alertTo === "all"
            ? t.form.allChannelsHint
            : t.form.someChannelsHint}
      </p>
      {alertTo === "picked" &&
        (channels.isError ? (
          <ErrorNote error={channels.error} />
        ) : (
          <fieldset
            aria-labelledby={`${id}-alertTo-label`}
            aria-describedby={error ? `${id}-channels-error` : undefined}
            className="min-w-0 divide-y rounded-lg border"
          >
            {(channels.data ?? []).map((channel, index) => {
              // The first box has the field's id, so a validation error focuses it.
              const checkboxId = index === 0 ? `${id}-channels` : `${id}-channel-${channel.id}`;
              return (
                <label
                  key={channel.id}
                  htmlFor={checkboxId}
                  className="flex cursor-pointer items-center gap-3 px-3 py-2.5 text-sm"
                >
                  <Checkbox
                    id={checkboxId}
                    checked={picked.includes(channel.id)}
                    onCheckedChange={(checked) =>
                      onPicked(
                        checked
                          ? [...picked, channel.id]
                          : picked.filter((channelId) => channelId !== channel.id),
                      )
                    }
                    aria-invalid={error ? true : undefined}
                  />
                  <ChannelIcon type={channel.type} className="size-4 text-muted-foreground" />
                  <span className="min-w-0 truncate">{channel.name}</span>
                  <span className="ml-auto shrink-0 text-muted-foreground text-xs">
                    {t.channels.types[channel.type]}
                  </span>
                </label>
              );
            })}
          </fieldset>
        ))}
      {error && (
        <p id={`${id}-channels-error`} className="text-problem text-xs">
          {error}
        </p>
      )}
    </div>
  );
}

function DurationInput({
  value,
  unit,
  units,
  onValue,
  onUnit,
  ...rest
}: {
  id: string;
  value: string;
  unit: Unit;
  units: Unit[];
  onValue: (value: string) => void;
  onUnit: (unit: Unit) => void;
  "aria-invalid"?: boolean;
  "aria-describedby"?: string;
}) {
  return (
    <div className="flex gap-2">
      <Input
        {...rest}
        type="number"
        inputMode="numeric"
        min={0}
        value={value}
        onChange={(event) => onValue(event.target.value)}
        className="w-28 tabular-nums"
      />
      <Select value={unit} onValueChange={(next) => onUnit(next as Unit)}>
        <SelectTrigger aria-label={t.form.unit} className="w-32">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {units.map((option) => (
            <SelectItem key={option} value={option}>
              {t.form.units[option]}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
