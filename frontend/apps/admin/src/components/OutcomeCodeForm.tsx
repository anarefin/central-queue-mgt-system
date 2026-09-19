"use client";

import type { OutcomeCode, OutcomeCodeInput, Site } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert, TextField } from "@qms/ui";
import { useId, useState, type FormEvent } from "react";
import { useSubmit } from "../lib/admin-support";
import { nameValues, TranslatedNameFields } from "./TranslatedNameFields";

interface OutcomeCodeFormProps {
  site: Site;
  /** Present when editing: the code is then fixed and only the labels and order change. */
  initial?: OutcomeCode;
  submitLabel: string;
  onSubmit: (input: OutcomeCodeInput) => Promise<unknown>;
  onDone: () => void;
  onCancel?: () => void;
}

/** An outcome code an agent can record on completion, with a label per language (FR-AGT-032, FR-AGT-033). */
export function OutcomeCodeForm({ site, initial, submitLabel, onSubmit, onDone, onCancel }: OutcomeCodeFormProps) {
  const { t } = useI18n();
  const id = useId();
  const [code, setCode] = useState(initial?.code ?? "");
  const [labels, setLabels] = useState(nameValues(site.enabled_languages, initial?.label_i18n));
  const [order, setOrder] = useState(String(initial?.display_order ?? 0));
  const { busy, error, run } = useSubmit();

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (await run(() => onSubmit({ code, label_i18n: labels, display_order: Number(order) }))) onDone();
  }

  return (
    <form className="qms-stack" onSubmit={submit}>
      {!initial && (
        <>
          <TextField id={`${id}-code`} label={t("catalogue.fields.code")} value={code} onChange={(e) => setCode(e.target.value)} />
          <p className="qms-muted">{t("catalogue.hint.code")}</p>
        </>
      )}
      <TranslatedNameFields
        id={`${id}-label`}
        label={t("catalogue.fields.label_i18n")}
        languages={site.enabled_languages}
        defaultLanguage={site.default_language}
        value={labels}
        onChange={setLabels}
      />
      <TextField id={`${id}-order`} type="number" label={t("catalogue.fields.display_order")} value={order} onChange={(e) => setOrder(e.target.value)} />
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
