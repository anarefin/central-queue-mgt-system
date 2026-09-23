"use client";

import type { WebhookEndpoint, WebhookEndpointInput, WebhookEventType } from "@qms/api-client";
import { WEBHOOK_EVENT_TYPES } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert, TextField } from "@qms/ui";
import { useId, useState, type FormEvent } from "react";
import { useSubmit } from "../lib/admin-support";

interface WebhookEndpointFormProps {
  initial?: WebhookEndpoint;
  submitLabel: string;
  onSubmit: (input: WebhookEndpointInput) => Promise<WebhookEndpoint>;
  /** Called with the created/updated endpoint once the submit succeeds, so the caller can show a freshly-issued
   * secret exactly once (FR-INT-020). */
  onDone: (result: WebhookEndpoint) => void;
  onCancel?: () => void;
}

/** A webhook endpoint (FR-INT-020): a description, the URL to POST to, and which §21.4 event types it subscribes to.
 * The secret is never edited here — it is set once at creation and changed only through "rotate secret". */
export function WebhookEndpointForm({ initial, submitLabel, onSubmit, onDone, onCancel }: WebhookEndpointFormProps) {
  const { t } = useI18n();
  const id = useId();
  const [description, setDescription] = useState(initial?.description ?? "");
  const [url, setUrl] = useState(initial?.url ?? "");
  const [eventTypes, setEventTypes] = useState<WebhookEventType[]>(initial?.event_types ?? []);
  const { busy, error, run } = useSubmit();

  function toggle(type: WebhookEventType) {
    setEventTypes((current) => (current.includes(type) ? current.filter((t) => t !== type) : [...current, type]));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    let created: WebhookEndpoint | undefined;
    const ok = await run(async () => {
      created = await onSubmit({ description, url, event_types: eventTypes });
    });
    if (ok && created) onDone(created);
  }

  return (
    <form className="flex flex-col gap-4" onSubmit={submit}>
      <TextField id={`${id}-description`} label={t("webhooks.fields.description")} value={description} onChange={(event) => setDescription(event.target.value)} />
      <TextField id={`${id}-url`} label={t("webhooks.fields.url")} type="url" value={url} onChange={(event) => setUrl(event.target.value)} />
      <fieldset className="flex flex-col gap-4">
        <legend>{t("webhooks.fields.eventTypes")}</legend>
        <div className="flex flex-col gap-4">
          {WEBHOOK_EVENT_TYPES.map((type) => (
            <label key={type} htmlFor={`${id}-type-${type}`} className="flex flex-wrap items-center justify-between gap-3">
              <input id={`${id}-type-${type}`} type="checkbox" checked={eventTypes.includes(type)} onChange={() => toggle(type)} />
              {t(`webhooks.eventType.${type}`)}
            </label>
          ))}
        </div>
      </fieldset>
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
