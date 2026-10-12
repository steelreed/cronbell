import { Bar, BarChart, LabelList, XAxis } from "recharts";
import {
  type ChartConfig,
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
} from "@/components/ui/chart";
import { useIsMobile } from "@/hooks/use-mobile";
import { t } from "@/lib/strings";
import type { DayStat } from "@/lib/types";

const config = {
  ok: { label: t.overview.chartLegendRuns, color: "var(--chart-1)" },
  failures: { label: t.overview.chartLegendFailures, color: "var(--chart-2)" },
} satisfies ChartConfig;

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

function dayLabel(date: string, today: string): string {
  if (date === today) return "Today";
  const [, month, day] = date.split("-").map(Number);
  return `${day} ${MONTHS[month - 1]}`;
}

/**
 * Finished runs per day, stacked: neutral for runs that succeeded, problem red for failures,
 * the day's total written on top. No grid and no Y axis; the numbers are on the bars.
 */
export function DailyChart({ days }: { days: DayStat[] }) {
  const narrow = useIsMobile();
  const today = days.at(-1)?.date ?? "";
  const data = days.map((day) => ({
    date: day.date,
    label: dayLabel(day.date, today),
    ok: day.runs - day.failures,
    failures: day.failures,
    runs: day.runs,
  }));
  const runs = days.reduce((sum, day) => sum + day.runs, 0);
  const failures = days.reduce((sum, day) => sum + day.failures, 0);
  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-muted-foreground text-xs">
        <span className="flex items-center gap-1.5">
          <span aria-hidden className="size-2 rounded-full bg-chart-1" />
          {t.overview.chartLegendRuns}
        </span>
        <span className="flex items-center gap-1.5">
          <span aria-hidden className="size-2 rounded-full bg-problem" />
          {t.overview.chartLegendFailures}
        </span>
        <span className="ml-auto tabular-nums">{t.overview.chartSummary(runs, failures)}</span>
      </div>
      <ChartContainer
        config={config}
        className="aspect-auto h-64 w-full"
        role="img"
        aria-label={t.overview.chartSummary(runs, failures)}
      >
        <BarChart
          data={data}
          margin={{ top: 18, right: 0, bottom: 0, left: 0 }}
          barCategoryGap="22%"
        >
          <XAxis
            dataKey="label"
            tickLine={false}
            axisLine={{ stroke: "var(--border)" }}
            tickMargin={8}
            interval="preserveStartEnd"
            minTickGap={12}
          />
          <ChartTooltip
            cursor={{ fill: "var(--accent)" }}
            content={<ChartTooltipContent labelKey="label" indicator="dot" />}
          />
          <Bar
            dataKey="ok"
            stackId="runs"
            fill="var(--color-ok)"
            isAnimationActive={false}
            maxBarSize={32}
          />
          <Bar
            dataKey="failures"
            stackId="runs"
            fill="var(--color-failures)"
            radius={[3, 3, 0, 0]}
            isAnimationActive={false}
            maxBarSize={32}
          >
            {!narrow && (
              <LabelList
                dataKey="runs"
                position="top"
                offset={6}
                className="fill-muted-foreground"
                fontSize={11}
                formatter={(value: unknown) =>
                  Number(value) > 0 ? Number(value).toLocaleString("en") : ""
                }
              />
            )}
          </Bar>
        </BarChart>
      </ChartContainer>
    </div>
  );
}
