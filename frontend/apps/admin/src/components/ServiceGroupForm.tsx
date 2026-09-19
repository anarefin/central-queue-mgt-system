"use client";

import type { ServiceGroup, ServiceGroupInput, Site } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert, TextField } from "@qms/ui";
import { useId, useState, type FormEvent } from "react";
import { useSubmit } from "../lib/admin-support";
import { nameValues, TranslatedNameFields } from "./TranslatedNameFields";

interface ServiceGroupFormProps {
  site: Site;
  initial?: ServiceGroup;
  submitLabel: string;
  onSubmit: (input: ServiceGroupInput) => Promise<unknown>;
  onDone: () => void;
  onCancel?: () => void;
}

/** A service group: names per language, token prefix and display order (SRS §7.2). */
export function ServiceGroupForm({ site, initial, submitLabel, onSubmit, onDone, onCancel }: ServiceGroupFormProps) {
  const { t } = useI18n();
  const id = useId();
  const [names, setNames] = useState(nameValues(site.enabled_languages, initial?.name_i18n));
  const [prefix, setPrefix] = useState(initial?.token_prefix ?? "");
  const [order, setOrder] = useState(String(initial?.display_order ?? 0));
  const { busy, error, run } = useSubmit();

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (await run(() => onSubmit({ name_i18n: names, token_prefix: prefix, display_order: Number(order) }))) onDone();
  }

  return (
    <form className="qms-stack" onSubmit={submit}>
      <TranslatedNameFields
        id={`${id}-name`}
        label={t("catalogue.fields.name_i18n")}
        languages={site.enabled_languages}
        defaultLanguage={site.default_language}
        value={names}
        onChange={setNames}
      />
      <TextField id={`${id}-prefix`} label={t("catalogue.fields.token_prefix")} value={prefix} onChange={(e) => setPrefix(e.target.value)} />
      <TextField
        id={`${id}-order`}
        type="number"
        label={t("catalogue.fields.display_order")}
        value={order}
        onChange={(e) => setOrder(e.target.value)}
      />
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
