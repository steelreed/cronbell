import { useEffect, useState } from "react";

/** The choice; "dark" is the default (Steelreed: dark first, with a light mode). */
export type Theme = "dark" | "light" | "system";

const KEY = "cronbell.theme";
const LIGHT = "(prefers-color-scheme: light)";

export function savedTheme(): Theme {
  try {
    const value = localStorage.getItem(KEY);
    if (value === "light" || value === "system") return value;
  } catch {
    // Private mode: nothing saved.
  }
  return "dark";
}

function resolve(theme: Theme): "dark" | "light" {
  if (theme !== "system") return theme;
  return typeof matchMedia === "function" && matchMedia(LIGHT).matches ? "light" : "dark";
}

export function applyTheme(theme: Theme) {
  document.documentElement.classList.toggle("dark", resolve(theme) === "dark");
  try {
    localStorage.setItem(KEY, theme);
  } catch {
    // Private mode: the choice lasts for this page only.
  }
}

/** The saved choice and a setter; follows the OS while the choice is "system". */
export function useTheme(): [Theme, (theme: Theme) => void] {
  const [theme, setTheme] = useState<Theme>(savedTheme);
  useEffect(() => {
    if (theme !== "system" || typeof matchMedia !== "function") return;
    const media = matchMedia(LIGHT);
    const onChange = () => applyTheme("system");
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, [theme]);
  return [
    theme,
    (next: Theme) => {
      applyTheme(next);
      setTheme(next);
    },
  ];
}
