import { CheckIcon, MonitorIcon, MoonIcon, SunIcon } from "lucide-react";
import type { ReactNode } from "react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { t } from "@/lib/strings";
import { type Theme, useTheme } from "@/lib/theme";

const OPTIONS: { value: Theme; label: string; icon: ReactNode }[] = [
  { value: "dark", label: t.theme.dark, icon: <MoonIcon aria-hidden /> },
  { value: "light", label: t.theme.light, icon: <SunIcon aria-hidden /> },
  { value: "system", label: t.theme.system, icon: <MonitorIcon aria-hidden /> },
];

export function ThemeIcon({ theme }: { theme: Theme }) {
  if (theme === "light") return <SunIcon aria-hidden />;
  if (theme === "system") return <MonitorIcon aria-hidden />;
  return <MoonIcon aria-hidden />;
}

/** Dark, light or system, all three visible with a check on the current one. */
export function ThemeMenu({
  trigger,
  align = "end",
  side = "bottom",
}: {
  trigger: (theme: Theme) => ReactNode;
  align?: "start" | "end";
  side?: "top" | "bottom" | "right";
}) {
  const [theme, setTheme] = useTheme();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>{trigger(theme)}</DropdownMenuTrigger>
      <DropdownMenuContent align={align} side={side} className="min-w-40">
        {OPTIONS.map((option) => (
          <DropdownMenuItem key={option.value} onSelect={() => setTheme(option.value)}>
            {option.icon}
            {option.label}
            {theme === option.value && <CheckIcon aria-hidden className="ml-auto" />}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
