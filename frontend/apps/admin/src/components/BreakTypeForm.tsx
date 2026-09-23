"use client";

import type { BreakType, BreakTypeInput } from "@qms/api-client";
import { SHIPPED_LANGUAGES } from "@qms/i18n";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert, TextField } from "@qms/ui";
import { useId, useState, type FormEvent } from "react";
import { useSubmit } from "../lib/admin-support";
import { nameValues, TranslatedNameFields } from "./TranslatedNameFields";

/** Break types belong to the organisation, not to a site, so their names follow the installed languages. */
const SYSTEM_DEFAULT_LANGUAGE = "en";

interface BreakTypeFormProps {
  initial?: BreakType;
  submitLabel: string;
  onSubmit: (input: BreakTypeInput) => Promise<unknown>;
  onDone: () => void;
  onCancel?: () => void;
}

/** A break type (FR-AGT-020): a name in each language and an optional longest duration in minutes. */
export function BreakTypeForm({ initial, submitLabel, onSubmit, onDone, onCancel }: BreakTypeFormProps) {
  const { t } = useI18n();
  const id = useId();
  const [names, setNames] = useState(nameValues(SHIPPED_LANGUAGES, initial?.name_i18n));
  const [max, setMax] = useState(initial?.max_minutes == null ? "" : String(initial.max_minutes));
  const { busy, error, run } = useSubmit();

  async function submit(event: FormEvent) {
    event.preventDefault();
    const input: BreakTypeInput = { name_i18n: names, max_minutes: max.trim() === "" ? null : Number(max) };
    if (await run(() => onSubmit(input))) onDone();
  }

  return (
    <form className="flex flex-col gap-4" onSubmit={submit}>
      <TranslatedNameFields
        id={`${id}-name`}
        label={t("catalogue.fields.name_i18n")}
        languages={SHIPPED_LANGUAGES}
        defaultLanguage={SYSTEM_DEFAULT_LANGUAGE}
        value={names}
        onChange={setNames}
      />
      <TextField id={`${id}-max`} type="number" min={1} label={t("catalogue.fields.max_minutes")} value={max} onChange={(e) => setMax(e.target.value)} />
      {error && <ErrorAlert>{error}</ErrorAlert>}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Button type="submit" disabled={busy}>
          {busy ? t("admin.action.saving") : submitLabel}
        </Button>
        {onCancel && (
          <Button variant="secondary" type="button" onClick={onCancel}>
            {t("admin.action.cancel")}
          </Button>
        )}
      </div>
    </form>
  );
}
