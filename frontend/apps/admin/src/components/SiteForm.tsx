"use client";

import type { SiteInput } from "@qms/api-client";
import { SHIPPED_LANGUAGES } from "@qms/i18n";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert, SelectField, TextField } from "@qms/ui";
import { useId, useState, type FormEvent } from "react";
import { languageName, useSubmit } from "../lib/admin-support";

interface SiteFormProps {
  initial?: SiteInput;
  submitLabel: string;
  onSubmit: (input: SiteInput) => Promise<unknown>;
  onDone: () => void;
  onCancel?: () => void;
}

const EMPTY: SiteInput = {
  name: "",
  code: "",
  timezone: "",
  address: "",
  default_language: SHIPPED_LANGUAGES[0] ?? "en",
  enabled_languages: [],
  clinical_sensitivity: false,
};

/** Site fields (FR-CFG-002, FR-I18N-002). Enabled languages are typed as a comma list because their order matters. */
export function SiteForm({ initial = EMPTY, submitLabel, onSubmit, onDone, onCancel }: SiteFormProps) {
  const { t } = useI18n();
  const id = useId();
  const [values, setValues] = useState({
    ...initial,
    enabled_languages: initial.enabled_languages.join(", "),
    clinical_sensitivity: initial.clinical_sensitivity ?? false,
  });
  const { busy, error, run } = useSubmit();
  const set = (field: keyof typeof values) => (event: { target: { value: string } }) => setValues({ ...values, [field]: event.target.value });

  async function submit(event: FormEvent) {
    event.preventDefault();
    const input: SiteInput = { ...values, enabled_languages: values.enabled_languages.split(/[\s,]+/).filter(Boolean) };
    if (await run(() => onSubmit(input))) onDone();
  }

  return (
    <form className="flex flex-col gap-4" onSubmit={submit}>
      <TextField id={`${id}-name`} label={t("sites.fields.name")} value={values.name} onChange={set("name")} />
      <TextField id={`${id}-code`} label={t("sites.fields.code")} value={values.code} onChange={set("code")} />
      <TextField
        id={`${id}-timezone`}
        label={t("sites.fields.timezone")}
        value={values.timezone}
        onChange={set("timezone")}
        placeholder="Asia/Dhaka"
       
      />
      <p className="text-fg-muted">{t("sites.hint.timezone")}</p>
      <TextField id={`${id}-address`} label={t("sites.fields.address")} value={values.address} onChange={set("address")} />
      <SelectField
        id={`${id}-language`}
        label={t("sites.fields.default_language")}
        value={values.default_language}
        onChange={set("default_language")}
        options={SHIPPED_LANGUAGES.map((code) => ({ value: code, label: languageName(t, code) }))}
      />
      <TextField
        id={`${id}-languages`}
        label={t("sites.fields.enabled_languages")}
        value={values.enabled_languages}
        onChange={set("enabled_languages")}
        placeholder="bn, en"
      />
      <p className="text-fg-muted">{t("sites.hint.languages")}</p>
      <label htmlFor={`${id}-clinical`} className="flex flex-wrap items-center justify-between gap-3">
        <input
          id={`${id}-clinical`}
          type="checkbox"
          checked={values.clinical_sensitivity}
          onChange={(event) => setValues({ ...values, clinical_sensitivity: event.target.checked })}
        />
        {t("sites.fields.clinical_sensitivity")}
      </label>
      <p className="text-fg-muted">{t("sites.hint.clinicalSensitivity")}</p>
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
