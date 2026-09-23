"use client";

import type { WebhookDeliveryStatus, WebhookDeliveryWithAttempts } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField, TextField } from "@qms/ui";
import { useId, useState } from "react";
import { describeError, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

const STATUSES: WebhookDeliveryStatus[] = ["queued", "sent", "failed"];

/** The webhook delivery log (ticket 57, FR-INT-021): every delivery, filterable by endpoint, event type and status,
 * each replayable on demand — a fresh attempt sent at once, whatever its current status. */
export function WebhookDeliveryLogCard() {
  const { t, formatTime } = useI18n();
  const { client } = useApi();
  const id = useId();
  const [endpointId, setEndpointId] = useState("");
  const [eventType, setEventType] = useState("");
  const [status, setStatus] = useState("");
  const [rows, setRows] = useState<WebhookDeliveryWithAttempts[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const replay = useSubmit();

  async function search() {
    if (!client) return;
    setBusy(true);
    setError(null);
    try {
      const result = await client.webhooks.deliveries.search({
        endpointId: endpointId || undefined,
        eventType: eventType || undefined,
        status: (status || undefined) as WebhookDeliveryStatus | undefined,
      });
      setRows(result.items);
    } catch (cause) {
      setError(cause);
    } finally {
      setBusy(false);
    }
  }

  async function replayDelivery(deliveryId: string) {
    if (!client) return;
    const ok = await replay.run(() => client.webhooks.deliveries.replay(deliveryId));
    if (ok) await search();
  }

  return (
    <Card>
      <h2 className="font-semibold text-fg">{t("webhooks.log.title")}</h2>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <TextField id={`${id}-endpoint`} label={t("webhooks.log.endpointId")} value={endpointId} onChange={(event) => setEndpointId(event.target.value)} />
        <TextField id={`${id}-type`} label={t("webhooks.log.eventType")} value={eventType} onChange={(event) => setEventType(event.target.value)} />
        <SelectField
          id={`${id}-status`}
          label={t("webhooks.log.status")}
          value={status}
          onChange={(event) => setStatus(event.target.value)}
          options={[{ value: "", label: t("webhooks.log.anyStatus") }, ...STATUSES.map((value) => ({ value, label: t(`webhooks.log.statusValue.${value}`) }))]}
        />
        <Button type="button" disabled={busy} onClick={() => void search()}>
          {t("webhooks.log.search")}
        </Button>
      </div>
      {error != null && <ErrorAlert>{describeError(t, error)}</ErrorAlert>}
      {replay.error && <ErrorAlert>{replay.error}</ErrorAlert>}
      {rows && rows.length === 0 && <p className="text-fg-muted">{t("webhooks.log.empty")}</p>}
      {rows && rows.length > 0 && (
        <ul className="m-0 list-none p-0 flex flex-col divide-y divide-border [&>li]:flex [&>li]:flex-wrap [&>li]:items-center [&>li]:justify-between [&>li]:gap-2 [&>li]:py-2.5">
          {rows.map(({ delivery }) => (
            <li key={delivery.id} className="flex flex-wrap items-center justify-between gap-3">
              <span>{delivery.event_type}</span>
              <span className="text-fg-muted">{t(`webhooks.log.statusValue.${delivery.status}`)}</span>
              <span className="text-fg-muted">{delivery.attempt_count}</span>
              <span className="text-fg-muted">{formatTime(new Date(delivery.created_at))}</span>
              <Button
                variant="secondary"
                type="button"
                disabled={replay.busy}
                aria-label={`${t("webhooks.log.replay")} ${delivery.event_type}`}
                onClick={() => void replayDelivery(delivery.id)}
              >
                {t("webhooks.log.replay")}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
