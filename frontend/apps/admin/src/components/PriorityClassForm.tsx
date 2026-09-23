"use client";

import type { PriorityClass, PriorityClassInput } from "@qms/api-client";
import { SHIPPED_LANGUAGES } from "@qms/i18n";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert, TextField } from "@qms/ui";
import { useId, useState, type FormEvent } from "react";
import { useSubmit } from "../lib/admin-support";
import { nameValues, TranslatedNameFields } from "./TranslatedNameFields";

/** Priority classes belong to the organisation, not to a site, so their names follow the installed languages. */
const SYSTEM_DEFAULT_LANGUAGE = "en";

interface PriorityClassFormProps {
  initial?: PriorityClass;
  submitLabel: string;
  onSubmit: (input: PriorityClassInput) => Promise<unknown>;
  onDone: () => void;
  onCancel?: () => void;
}

/**
 * A Priority class (FR-QUE-010): names, Head start in minutes, optional maximum wait and optional token prefix override.
 * The default class keeps a Head start of 0 and takes no prefix, so those inputs are left out for it.
 */
export function PriorityClassForm({ initial, submitLabel, onSubmit, onDone, onCancel }: PriorityClassFormProps) {
  const { t } = useI18n();
  const id = useId();
  const isDefault = initial?.is_default ?? false;
  const [names, setNames] = useState(nameValues(SHIPPED_LANGUAGES, initial?.name_i18n));
  const [headstart, setHeadstart] = useState(String(initial?.headstart_minutes ?? 0));
  const [maxWait, setMaxWait] = useState(initial?.max_wait_minutes == null ? "" : String(initial.max_wait_minutes));
  const [prefix, setPrefix] = useState(initial?.token_prefix_override ?? "");
  const { busy, error, run } = useSubmit();

  async function submit(event: FormEvent) {
    event.preventDefault();
    const input: PriorityClassInput = {
      name_i18n: names,
      headstart_minutes: isDefault ? 0 : Number(headstart),
      max_wait_minutes: maxWait.trim() === "" ? null : Number(maxWait),
      token_prefix_override: isDefault || prefix.trim() === "" ? null : prefix.trim(),
    };
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
      {!isDefault && (
        <TextField
          id={`${id}-headstart`}
          type="number"
          min={0}
          label={t("catalogue.fields.headstart_minutes")}
          value={headstart}
          onChange={(e) => setHeadstart(e.target.value)}
        />
      )}
      <TextField
        id={`${id}-maxwait`}
        type="number"
        min={1}
        label={t("catalogue.fields.max_wait_minutes")}
        value={maxWait}
        onChange={(e) => setMaxWait(e.target.value)}
      />
      {!isDefault && (
        <TextField
          id={`${id}-prefix`}
          label={t("catalogue.fields.token_prefix_override")}
          value={prefix}
          onChange={(e) => setPrefix(e.target.value)}
        />
      )}
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
