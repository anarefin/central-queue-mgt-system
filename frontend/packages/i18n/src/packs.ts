import bn from "./packs/bn.json";
import en from "./packs/en.json";

export type Numerals = "latn" | "beng";

export interface LanguageMeta {
  /** BCP 47 locale used for dates, times, numbers and currency (FR-I18N-021). */
  locale: string;
  /** Digit set for ordinary numbers, per language pack (FR-I18N-020). Token numbers ignore this. */
  numerals: Numerals;
}

export interface Pack {
  messages: Record<string, string>;
  meta: LanguageMeta;
}

/** English and Bangla ship complete in the bundle; other packs load at runtime (see loadExtraPacks). */
export const SHIPPED_PACKS: Record<string, Pack> = {
  en: { messages: en, meta: { locale: "en-US", numerals: "latn" } },
  bn: { messages: bn, meta: { locale: "bn-BD", numerals: "beng" } },
};

export const SHIPPED_LANGUAGES = Object.keys(SHIPPED_PACKS);
