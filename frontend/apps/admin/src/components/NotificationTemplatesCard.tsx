"use client";

import type { NotificationTriggerCatalogueEntry } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField, TextField } from "@qms/ui";
import { useEffect, useId, useState } from "react";
import { describeError, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

const CHANNELS = ["in_app", "staff_alert", "web_push", "email"];
const LANGUAGES = ["en", "bn"];

/**
 * One template: trigger x channel x language, with the fixed variables that trigger allows, unknown ones rejected
 * at save (FR-NTF-020, FR-NTF-021), and a preview against sample values.
 */
export function NotificationTemplatesCard() {
  const { t } = useI18n();
  const { client } = useApi();
  const id = useId();
  const catalogue = useList<NotificationTriggerCatalogueEntry>(client ? () => client.notifications.catalogue() : null, [client]);
  const [triggerKey, setTriggerKey] = useState("");
  const [channel, setChannel] = useState<string>("in_app");
  const [templateLanguage, setTemplateLanguage] = useState<string>("en");
  const [subject, setSubject] = useState("");
  const [body, setBody] = useState("");
  const [preview, setPreview] = useState<{ subject: string | null; body: string } | null>(null);
  const { busy, error, run, clearError } = useSubmit();

  const selected = catalogue.items?.find((entry) => entry.trigger_key === triggerKey);

  useEffect(() => {
    const first = catalogue.items?.[0];
    if (first && !triggerKey) setTriggerKey(first.trigger_key);
  }, [catalogue.items, triggerKey]);

  useEffect(() => {
    if (!client || !triggerKey) return;
    let cancelled = false;
    setPreview(null);
    clearError();
    client.notifications
      .templatesForTrigger(triggerKey)
      .then((result) => {
        if (cancelled) return;
        const existing = result.items.find((tpl) => tpl.channel === channel && tpl.language === templateLanguage);
        setSubject(existing?.subject ?? "");
        setBody(existing?.body ?? "");
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, triggerKey, channel, templateLanguage]);

  async function save() {
    if (!client || !triggerKey) return;
    const ok = await run(() => client.notifications.saveTemplate(triggerKey, channel, templateLanguage, { subject: subject || null, body }));
    if (ok) setPreview(null);
  }

  async function showPreview() {
    if (!client || !triggerKey) return;
    await run(async () => setPreview(await client.notifications.previewTemplate(triggerKey, channel, templateLanguage)));
  }

  return (
    <Card>
      <h2 className="qms-heading">{t("notifications.templates.title")}</h2>
      {catalogue.error != null && <ErrorAlert>{describeError(t, catalogue.error)}</ErrorAlert>}
      {catalogue.items && catalogue.items.length > 0 && (
        <SelectField
          id={`${id}-trigger`}
          label={t("notifications.templates.trigger")}
          value={triggerKey}
          onChange={(event) => setTriggerKey(event.target.value)}
          options={catalogue.items.map((entry) => ({ value: entry.trigger_key, label: t(`notifications.trigger.${entry.trigger_key}`) }))}
        />
      )}
      <SelectField
        id={`${id}-channel`}
        label={t("notifications.templates.channel")}
        value={channel}
        onChange={(event) => setChannel(event.target.value)}
        options={CHANNELS.map((value) => ({ value, label: t(`notifications.channel.${value}`) }))}
      />
      <SelectField
        id={`${id}-language`}
        label={t("notifications.templates.language")}
        value={templateLanguage}
        onChange={(event) => setTemplateLanguage(event.target.value)}
        options={LANGUAGES.map((value) => ({ value, label: t(`languages.${value}`) }))}
      />
      {selected && (
        <p className="qms-muted">{t("notifications.templates.variables", { variables: selected.variables.map((v) => `{{${v}}}`).join(", ") })}</p>
      )}
      <TextField id={`${id}-subject`} label={t("notifications.templates.subject")} value={subject} onChange={(event) => setSubject(event.target.value)} />
      <div>
        <label className="qms-label" htmlFor={`${id}-body`}>
          {t("notifications.templates.body")}
        </label>
        <textarea id={`${id}-body`} className="qms-input" rows={4} value={body} onChange={(event) => setBody(event.target.value)} />
      </div>
      {error && <ErrorAlert>{error}</ErrorAlert>}
      <div className="qms-row">
        <Button type="button" disabled={busy || !triggerKey} onClick={() => void save()}>
          {busy ? t("admin.action.saving") : t("admin.action.save")}
        </Button>
        <Button type="button" variant="secondary" disabled={busy || !triggerKey} onClick={() => void showPreview()}>
          {t("notifications.templates.preview")}
        </Button>
      </div>
      {preview && (
        <Card>
          {preview.subject && <p>
            <strong>{preview.subject}</strong>
          </p>}
          <p>{preview.body}</p>
        </Card>
      )}
    </Card>
  );
}
