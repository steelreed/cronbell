import type { ReactNode } from "react";

/** Label and value pairs: stacked in a narrow container, side by side from 24rem. */
export function DescriptionList({ items }: { items: { label: string; value: ReactNode }[] }) {
  return (
    <dl className="@container space-y-3 text-sm">
      {items.map((item) => (
        <div
          key={item.label}
          className="grid gap-0.5 @xs:grid-cols-[7.5rem_minmax(0,1fr)] @xs:items-baseline @xs:gap-4"
        >
          <dt className="text-muted-foreground">{item.label}</dt>
          <dd className="min-w-0 [overflow-wrap:anywhere]">{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** An empty value. */
export function Dash() {
  return <span className="text-muted-foreground">—</span>;
}
