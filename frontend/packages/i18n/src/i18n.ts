import { toWesternDigits } from "./numerals";
import type { Pack } from "./packs";

export interface I18nConfig {
  packs: Record<string, Pack>;
  language: string;
  siteDefault?: string;
  systemDefault: string;
  /** Per-site 12- or 24-hour clock (FR-I18N-021). */
  clock?: "12h" | "24h";
}

export interface I18n {
  language: string;
  t: (key: string, params?: Record<string, string | number>) => string;
  formatNumber: (value: number) => string;
  formatToken: (token: string) => string;
  formatDate: (value: Date, options?: { timeZone?: string }) => string;
  formatTime: (value: Date, options?: { timeZone?: string }) => string;
  formatCurrency: (amount: number, currency: string) => string;
}

const FALLBACK_KEY = "fallback.text";
const LAST_RESORT = "Text unavailable";

function present(value: string | undefined): value is string {
  return value !== undefined && value.trim() !== "";
}

export function createI18n(config: I18nConfig): I18n {
  const { packs, language, systemDefault } = config;
  const chain = [...new Set([language, config.siteDefault ?? systemDefault, systemDefault])];
  const meta = (packs[language] ?? packs[systemDefault])?.meta ?? { locale: "en-US", numerals: "latn" as const };
  const locale = `${meta.locale}-u-nu-${meta.numerals}`;
  const hour12 = (config.clock ?? "12h") === "12h";

  const lookup = (key: string): string | undefined => {
    for (const candidate of chain) {
      const value = packs[candidate]?.messages[key];
      if (present(value)) return value;
    }
    return undefined;
  };

  const t: I18n["t"] = (key, params) => {
    // Never a raw key or an empty string (FR-I18N-011).
    const template = lookup(key) ?? packs[systemDefault]?.messages[FALLBACK_KEY] ?? LAST_RESORT;
    return template.replace(/\{(\w+)\}/g, (whole, name: string) =>
      params && name in params ? String(params[name]) : whole,
    );
  };

  return {
    language,
    t,
    formatNumber: (value) => new Intl.NumberFormat(locale).format(value),
    formatToken: toWesternDigits,
    formatDate: (value, options) =>
      new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeZone: options?.timeZone }).format(value),
    formatTime: (value, options) =>
      new Intl.DateTimeFormat(locale, {
        hour: hour12 ? "numeric" : "2-digit",
        minute: "2-digit",
        hour12,
        timeZone: options?.timeZone,
      }).format(value),
    formatCurrency: (amount, currency) => new Intl.NumberFormat(locale, { style: "currency", currency }).format(amount),
  };
}
