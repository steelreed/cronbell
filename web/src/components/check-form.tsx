import { type FormEvent, useId, useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { t } from "@/lib/strings";
import type { Check, CheckDefinition } from "@/lib/types";
import {
  browserTimeZone,
  type CheckFormErrors,
  type CheckFormValues,
  splitSeconds,
  supportedTimeZones,
  toDefinition,
  type Unit,
  validateCheckForm,
} from "@/lib/validation";
import { FormField } from "./form-field";
import { ErrorNote } from "./page";

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
    timeZone: check.timeZone,
    grace: grace.value || "0",
    graceUnit: grace.unit,
    maxRuntime: maxRuntime.value,
    maxRuntimeUnit: maxRuntime.unit,
  };
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
  const zones = useMemo(() => supportedTimeZones(), []);
  const [values, setValues] = useState<CheckFormValues>(() => initialValues(check));
  const [errors, setErrors] = useState<CheckFormErrors>({});
  const set = <K extends keyof CheckFormValues>(key: K, value: CheckFormValues[K]) =>
    setValues((current) => ({ ...current, [key]: value }));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validateCheckForm(values, zones);
    setErrors(found);
    if (Object.keys(found).length === 0) onSubmit(values.slug, toDefinition(values));
  };

  const field = (key: keyof CheckFormValues) => ({
    id: `${id}-${key}`,
    "aria-invalid": errors[key] ? true : undefined,
    "aria-describedby": errors[key] ? `${id}-${key}-error` : `${id}-${key}-hint`,
  });

  return (
    <form onSubmit={submit} noValidate className="max-w-2xl space-y-6">
      <div className="grid gap-4 sm:grid-cols-2">
        <FormField id={`${id}-slug`} label={t.form.slug} hint={t.form.slugHint} error={errors.slug}>
          <Input
            {...field("slug")}
            value={values.slug}
            onChange={(event) => set("slug", event.target.value)}
            disabled={Boolean(check)}
            className="font-mono"
            placeholder="nightly-backup"
            autoFocus={!check}
            spellCheck={false}
            autoCapitalize="off"
          />
        </FormField>
        <FormField id={`${id}-name`} label={t.form.name} hint={t.form.nameHint} error={errors.name}>
          <Input
            {...field("name")}
            value={values.name}
            onChange={(event) => set("name", event.target.value)}
            placeholder="Nightly backup"
          />
        </FormField>
      </div>

      <fieldset className="space-y-3">
        <legend className="mb-2 font-medium text-sm">{t.form.schedule}</legend>
        <Tabs
          value={values.kind}
          onValueChange={(kind) => set("kind", kind as CheckFormValues["kind"])}
        >
          <TabsList>
            <TabsTrigger value="CRON">{t.form.cron}</TabsTrigger>
            <TabsTrigger value="PERIOD">{t.form.period}</TabsTrigger>
          </TabsList>
        </Tabs>
        <div className="grid gap-4 sm:grid-cols-2">
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
            <FormField id={`${id}-period`} label={t.form.period} error={errors.period}>
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
            <Input
              {...field("timeZone")}
              list={`${id}-zones`}
              value={values.timeZone}
              onChange={(event) => set("timeZone", event.target.value)}
              spellCheck={false}
              autoCapitalize="off"
            />
            <datalist id={`${id}-zones`}>
              {zones.map((zone) => (
                <option key={zone} value={zone} />
              ))}
            </datalist>
          </FormField>
        </div>
      </fieldset>

      <div className="grid gap-4 sm:grid-cols-2">
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

      {Boolean(error) && <ErrorNote error={error} />}
      <div className="flex gap-2">
        <Button type="submit" disabled={pending}>
          {pending ? t.common.saving : check ? t.common.save : t.form.create}
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel}>
          {t.common.cancel}
        </Button>
      </div>
    </form>
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
      <select
        aria-label="Unit"
        value={unit}
        onChange={(event) => onUnit(event.target.value as Unit)}
        className="h-8 rounded-lg border border-input bg-transparent px-2 text-sm dark:bg-input/30"
      >
        {units.map((option) => (
          <option key={option} value={option}>
            {t.form.units[option]}
          </option>
        ))}
      </select>
    </div>
  );
}
