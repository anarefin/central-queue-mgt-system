"use client";

import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { createI18n, type I18n } from "./i18n";
import { loadExtraPacks } from "./extra";
import { SHIPPED_PACKS, type Pack } from "./packs";
import { resolveLanguage } from "./resolve";

const I18nContext = createContext<I18n | null>(null);

export interface I18nProviderProps {
  children: ReactNode;
  /** The signed-in user's stored preference, when known. */
  userLanguage?: string | null;
  siteDefault?: string;
  systemDefault?: string;
  clock?: "12h" | "24h";
  /** Fetches packs an installation added at runtime. Pass `false` to skip (tests). */
  loadExtra?: boolean;
}

export function I18nProvider({
  children,
  userLanguage,
  siteDefault,
  systemDefault = "en",
  clock,
  loadExtra = true,
}: I18nProviderProps) {
  const [extra, setExtra] = useState<Record<string, Pack>>({});
  useEffect(() => {
    if (!loadExtra) return;
    let cancelled = false;
    void loadExtraPacks().then((packs) => {
      if (!cancelled) setExtra(packs);
    });
    return () => {
      cancelled = true;
    };
  }, [loadExtra]);

  const i18n = useMemo(() => {
    const packs = { ...SHIPPED_PACKS, ...extra };
    const device = typeof navigator === "undefined" ? [] : navigator.languages;
    const language = resolveLanguage({
      user: userLanguage,
      device,
      site: siteDefault,
      system: systemDefault,
      enabled: Object.keys(packs),
    });
    return createI18n({ packs, language, siteDefault, systemDefault, clock });
  }, [extra, userLanguage, siteDefault, systemDefault, clock]);

  useEffect(() => {
    document.documentElement.lang = i18n.language;
  }, [i18n.language]);

  return <I18nContext.Provider value={i18n}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18n {
  const value = useContext(I18nContext);
  if (!value) throw new Error("useI18n must be used inside <I18nProvider>");
  return value;
}
