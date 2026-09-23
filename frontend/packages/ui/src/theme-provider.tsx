"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

export type ThemeChoice = "system" | "light" | "dark";
type ResolvedTheme = "light" | "dark";

const STORAGE_KEY = "qms-theme";

function loadThemeChoice(): ThemeChoice {
  if (typeof window === "undefined") return "system";
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    return raw === "light" || raw === "dark" || raw === "system" ? raw : "system";
  } catch {
    return "system";
  }
}

/** A display preference, not a secret, so unlike the refresh credential (API-017) this may live in localStorage. */
function saveThemeChoice(choice: ThemeChoice): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(STORAGE_KEY, choice);
  } catch {
    // No persistence this run; the in-memory choice still applies until reload.
  }
}

function systemPrefersDark(): boolean {
  return typeof window !== "undefined" && typeof window.matchMedia === "function" && window.matchMedia("(prefers-color-scheme: dark)").matches;
}

interface ThemeState {
  choice: ThemeChoice;
  resolved: ResolvedTheme;
  setChoice: (choice: ThemeChoice) => void;
}

const ThemeContext = createContext<ThemeState | null>(null);

/**
 * Tracks the theme choice (system/light/dark), mirrors it to `data-theme` on `<html>` for theme.css's selectors, and
 * persists it under `qms-theme`. Kiosk and display do not mount this: they stay on the light, high-contrast theme
 * regardless of the device's OS preference (ticket 62).
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [choice, setChoiceState] = useState<ThemeChoice>(() => loadThemeChoice());
  const [systemDark, setSystemDark] = useState<boolean>(() => systemPrefersDark());

  useEffect(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") return;
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => setSystemDark(media.matches);
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, []);

  useEffect(() => {
    if (typeof document === "undefined") return;
    if (choice === "system") {
      document.documentElement.removeAttribute("data-theme");
    } else {
      document.documentElement.dataset.theme = choice;
    }
  }, [choice]);

  const setChoice = useCallback((next: ThemeChoice) => {
    setChoiceState(next);
    saveThemeChoice(next);
  }, []);

  const resolved: ResolvedTheme = choice === "system" ? (systemDark ? "dark" : "light") : choice;
  const value = useMemo<ThemeState>(() => ({ choice, resolved, setChoice }), [choice, resolved, setChoice]);

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): ThemeState {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error("useTheme must be used within a ThemeProvider");
  return ctx;
}

/**
 * Reads `qms-theme` and sets `data-theme` before first paint, so an explicit light/dark pick never flashes the
 * other theme while ThemeProvider's own effect is still pending. Renders a plain `<script>`, so it belongs in
 * `<head>`, ahead of `children`.
 */
// A fixed string, not interpolated from any prop, request, or storage value: safe to inline verbatim, unlike
// dangerouslySetInnerHTML with any dynamic or user-influenced content.
const THEME_INIT_SCRIPT = `(function(){try{var v=window.localStorage.getItem(${JSON.stringify(STORAGE_KEY)});if(v==="light"||v==="dark"){document.documentElement.dataset.theme=v;}}catch(e){}})();`;

export function ThemeScript() {
  return <script dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }} />;
}
