"use client";

import type { SiteInput } from "@qms/api-client";
import { SHIPPED_LANGUAGES } from "@qms/i18n";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert, parseLanguageList, requiredText, SelectField, siteCode, siteDefaultLanguage, siteEnabledLanguages, siteTimezone, TextField } from "@qms/ui";
import { useId, useState, type FormEvent } from "react";
import { languageName, useFormValidation, useSubmit } from "../lib/admin-support";

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

  const fieldIds = { name: `${id}-name`, code: `${id}-code`, timezone: `${id}-timezone`, address: `${id}-address`, enabled_languages: `${id}-languages` };
  const validation = useFormValidation<typeof values>(
    {
      name: (v) => requiredText(v.name, 200),
      code: (v) => siteCode(v.code),
      timezone: (v) => siteTimezone(v.timezone),
      address: (v) => requiredText(v.address, 500),
      enabled_languages: (v) => siteEnabledLanguages(v.enabled_languages) ?? siteDefaultLanguage(v.default_language, v.enabled_languages),
    },
    fieldIds,
  );
  const onBlur = (field: keyof typeof fieldIds) => () => validation.validateField(field, values);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!validation.validateAll(values)) return;
    const input: SiteInput = { ...values, enabled_languages: parseLanguageList(values.enabled_languages) };
    if (await run(() => onSubmit(input))) onDone();
  }

  return (
    <form className="flex flex-col gap-4" onSubmit={submit}>
      <TextField id={fieldIds.name} label={t("sites.fields.name")} value={values.name} onChange={set("name")} onBlur={onBlur("name")} error={validation.message("name")} />
      <TextField id={fieldIds.code} label={t("sites.fields.code")} value={values.code} onChange={set("code")} onBlur={onBlur("code")} error={validation.message("code")} />
      <TextField
        id={fieldIds.timezone}
        label={t("sites.fields.timezone")}
        value={values.timezone}
        onChange={set("timezone")}
        onBlur={onBlur("timezone")}
        error={validation.message("timezone")}
        placeholder="Asia/Dhaka"

      />
      <p className="text-fg-muted">{t("sites.hint.timezone")}</p>
      <TextField
        id={fieldIds.address}
        label={t("sites.fields.address")}
        value={values.address}
        onChange={set("address")}
        onBlur={onBlur("address")}
        error={validation.message("address")}
      />
      <SelectField
        id={`${id}-language`}
        label={t("sites.fields.default_language")}
        value={values.default_language}
        onChange={(event) => {
          setValues({ ...values, default_language: event.target.value });
          validation.validateField("enabled_languages", { ...values, default_language: event.target.value });
        }}
        options={SHIPPED_LANGUAGES.map((code) => ({ value: code, label: languageName(t, code) }))}
      />
      <TextField
        id={fieldIds.enabled_languages}
        label={t("sites.fields.enabled_languages")}
        value={values.enabled_languages}
        onChange={set("enabled_languages")}
        onBlur={onBlur("enabled_languages")}
        error={validation.message("enabled_languages")}
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
