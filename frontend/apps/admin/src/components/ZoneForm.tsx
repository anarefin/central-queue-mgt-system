"use client";

import type { ZoneInput } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert, optionalText, requiredText, TextField } from "@qms/ui";
import { useId, useState, type FormEvent } from "react";
import { useFormValidation, useSubmit } from "../lib/admin-support";

interface ZoneFormProps {
  initial?: ZoneInput;
  submitLabel: string;
  onSubmit: (input: ZoneInput) => Promise<unknown>;
  onDone: () => void;
  onCancel?: () => void;
}

/** A zone is labelled by floor and, optionally, building (FR-CFG-003, ADR-0002). */
export function ZoneForm({ initial = { name: "", floor_label: "", building_label: "" }, submitLabel, onSubmit, onDone, onCancel }: ZoneFormProps) {
  const { t } = useI18n();
  const id = useId();
  const [values, setValues] = useState({ name: initial.name, floor_label: initial.floor_label, building_label: initial.building_label ?? "" });
  const { busy, error, run } = useSubmit();
  const set = (field: keyof typeof values) => (event: { target: { value: string } }) => setValues({ ...values, [field]: event.target.value });

  const fieldIds = { name: `${id}-name`, floor_label: `${id}-floor`, building_label: `${id}-building` };
  const validation = useFormValidation<typeof values>(
    {
      name: (v) => requiredText(v.name, 200),
      floor_label: (v) => requiredText(v.floor_label, 100),
      building_label: (v) => optionalText(v.building_label, 100),
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
      <TextField id={fieldIds.name} label={t("sites.fields.name")} value={values.name} onChange={set("name")} onBlur={onBlur("name")} error={validation.message("name")} />
      <TextField
        id={fieldIds.floor_label}
        label={t("sites.fields.floor_label")}
        value={values.floor_label}
        onChange={set("floor_label")}
        onBlur={onBlur("floor_label")}
        error={validation.message("floor_label")}
      />
      <TextField
        id={fieldIds.building_label}
        label={t("sites.fields.building_label")}
        value={values.building_label}
        onChange={set("building_label")}
        onBlur={onBlur("building_label")}
        error={validation.message("building_label")}
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
