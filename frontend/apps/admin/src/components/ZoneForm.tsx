"use client";

import type { ZoneInput } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert, TextField } from "@qms/ui";
import { useId, useState, type FormEvent } from "react";
import { useSubmit } from "../lib/admin-support";

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

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (await run(() => onSubmit(values))) onDone();
  }

  return (
    <form className="qms-stack" onSubmit={submit}>
      <TextField id={`${id}-name`} label={t("sites.fields.name")} value={values.name} onChange={set("name")} />
      <TextField id={`${id}-floor`} label={t("sites.fields.floor_label")} value={values.floor_label} onChange={set("floor_label")} />
      <TextField id={`${id}-building`} label={t("sites.fields.building_label")} value={values.building_label} onChange={set("building_label")} />
      {error && <ErrorAlert>{error}</ErrorAlert>}
      <div className="qms-row">
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
