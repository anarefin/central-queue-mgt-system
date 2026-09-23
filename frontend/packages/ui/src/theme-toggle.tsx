"use client";

import { cn } from "./cn";
import { useTheme, type ThemeChoice } from "./theme-provider";

const OPTIONS: readonly ThemeChoice[] = ["system", "light", "dark"];

/**
 * Cycles the theme between system/light/dark (ticket 62). Packages/ui does not depend on @qms/i18n, so every label
 * is supplied already-translated by the caller, the same convention as TextField's `label` prop.
 */
export function ThemeToggle({ groupLabel, labels }: { groupLabel: string; labels: Record<ThemeChoice, string> }) {
  const { choice, setChoice } = useTheme();
  return (
    <div role="group" aria-label={groupLabel} className="inline-flex gap-1 rounded-md border border-border p-1">
      {OPTIONS.map((option) => (
        <button
          key={option}
          type="button"
          aria-pressed={choice === option}
          onClick={() => setChoice(option)}
          className={cn(
            "rounded-sm px-2 py-1 text-sm motion-safe:transition-colors",
            "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary",
            choice === option ? "bg-primary text-primary-fg" : "text-fg-muted hover:bg-surface-muted",
          )}
        >
          {labels[option]}
        </button>
      ))}
    </div>
  );
}
