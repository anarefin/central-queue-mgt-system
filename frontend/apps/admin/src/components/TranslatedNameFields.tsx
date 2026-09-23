"use client";

import { useI18n } from "@qms/i18n/react";
import { TextField } from "@qms/ui";
import { languageName } from "../lib/admin-support";

interface TranslatedNameFieldsProps {
  id: string;
  /** The field's label, for example "Name". */
  label: string;
  /** The site's enabled languages, in display order: one input each (FR-I18N-010). */
  languages: string[];
  defaultLanguage: string;
  value: Record<string, string>;
  onChange: (value: Record<string, string>) => void;
}

/**
 * One input per enabled language. A blank one only warns, because a missing translation falls back to the site's
 * default language; it never blocks saving (FR-I18N-010, FR-I18N-011).
 */
export function TranslatedNameFields({ id, label, languages, defaultLanguage, value, onChange }: TranslatedNameFieldsProps) {
  const { t } = useI18n();
  const blank = languages.filter((language) => (value[language] ?? "").trim() === "");
  return (
    <div className="flex flex-col gap-4">
      {languages.map((language) => (
        <TextField
          key={language}
          id={`${id}-${language}`}
          label={t("catalogue.names.input", { field: label, language: languageName(t, language) })}
          value={value[language] ?? ""}
          lang={language}
          onChange={(event) => onChange({ ...value, [language]: event.target.value })}
        />
      ))}
      {blank.length > 0 && (
        <p className="text-warn" role="status">
          {t("catalogue.names.missing", {
            languages: blank.map((language) => languageName(t, language)).join(", "),
            default: languageName(t, defaultLanguage),
          })}
        </p>
      )}
    </div>
  );
}

/** Starting values for the inputs of a set of names: what exists, and blanks for the rest. */
export function nameValues(languages: string[], existing: Record<string, string> = {}): Record<string, string> {
  return Object.fromEntries(languages.map((language) => [language, existing[language] ?? ""]));
}
