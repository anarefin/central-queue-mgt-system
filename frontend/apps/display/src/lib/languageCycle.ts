"use client";

import { useEffect, useState } from "react";

export interface LanguageCycle {
  /** The language a layout should render content in right now. */
  language: string;
  /** True when `cycleSeconds` is 0: every language of the cycle should render together, not one at a time. */
  sideBySide: boolean;
  /** The whole cycle, in its configured order; only meaningful (more than one entry) when `sideBySide` is true. */
  languages: string[];
}

/**
 * FR-I18N-005: "Displays MUST cycle through enabled languages at a configurable interval, or render side by side
 * where the layout allows." `cycleSeconds` is a display's own `language_cycle_seconds` (ticket 30); 0 renders side
 * by side instead of cycling. Pure and independent of the app's own resolved UI language (`useI18n`, FR-I18N-003):
 * this only decides which of the zone's own translated content (Service names, notice-board text) a board shows.
 */
export function activeLanguage(languageCycle: string[], cycleSeconds: number, elapsedMs: number): LanguageCycle {
  const languages = languageCycle.length > 0 ? languageCycle : ["en"];
  if (cycleSeconds <= 0) return { language: languages[0]!, sideBySide: languages.length > 1, languages };
  const index = Math.floor(elapsedMs / (cycleSeconds * 1000)) % languages.length;
  return { language: languages[index]!, sideBySide: false, languages };
}

/** The stateful, ticking version of {@link activeLanguage} for a mounted board. */
export function useLanguageCycle(languageCycle: string[], cycleSeconds: number): LanguageCycle {
  const [elapsedMs, setElapsedMs] = useState(0);

  useEffect(() => {
    if (cycleSeconds <= 0 || languageCycle.length <= 1) return;
    const started = Date.now();
    const interval = setInterval(() => setElapsedMs(Date.now() - started), 1000);
    return () => clearInterval(interval);
  }, [languageCycle.join(","), cycleSeconds]);

  return activeLanguage(languageCycle, cycleSeconds, elapsedMs);
}
