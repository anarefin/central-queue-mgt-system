"use client";

import type { CounterInput } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, counterLabel, ErrorAlert, optionalText, TextField } from "@qms/ui";
import { useId, useState, type FormEvent } from "react";
import { useFormValidation, useSubmit } from "../lib/admin-support";

interface CounterFormProps {
  initial?: CounterInput;
  submitLabel: string;
  onSubmit: (input: CounterInput) => Promise<unknown>;
  onDone: () => void;
  onCancel?: () => void;
}

/** A counter has a short display label and an optional physical location note (FR-CFG-004). */
export function CounterForm({ initial = { label: "", location_note: "" }, submitLabel, onSubmit, onDone, onCancel }: CounterFormProps) {
  const { t } = useI18n();
  const id = useId();
  const [values, setValues] = useState({ label: initial.label, location_note: initial.location_note ?? "" });
  const { busy, error, run } = useSubmit();
  const set = (field: keyof typeof values) => (event: { target: { value: string } }) => setValues({ ...values, [field]: event.target.value });

  const fieldIds = { label: `${id}-label`, location_note: `${id}-note` };
  const validation = useFormValidation<typeof values>(
    {
      label: (v) => counterLabel(v.label),
      location_note: (v) => optionalText(v.location_note, 300),
    },
    fieldIds,
  );
  const onBlur = (field: keyof typeof fieldIds) => () => validation.validateField(field, values);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!validation.validateAll(values)) return;
    if (await run(() => onSubmit(values))) onDone();
  }

  return (
    <form className="flex flex-col gap-4" onSubmit={submit}>
      <TextField id={fieldIds.label} label={t("sites.fields.label")} value={values.label} onChange={set("label")} onBlur={onBlur("label")} error={validation.message("label")} />
      <TextField
        id={fieldIds.location_note}
        label={t("sites.fields.location_note")}
        value={values.location_note}
        onChange={set("location_note")}
        onBlur={onBlur("location_note")}
        error={validation.message("location_note")}
      />
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
