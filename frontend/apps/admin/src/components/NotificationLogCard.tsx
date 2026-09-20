"use client";

import type { NotificationMessageStatus, NotificationMessageWithAttempts } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField, TextField } from "@qms/ui";
import { useId, useState } from "react";
import { describeError } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

const STATUSES: NotificationMessageStatus[] = ["queued", "sent", "failed", "suppressed"];

/** The delivery log (FR-NTF-032): every message, filterable by ticket, visitor and status. */
export function NotificationLogCard() {
  const { t, formatTime } = useI18n();
  const { client } = useApi();
  const id = useId();
  const [ticketId, setTicketId] = useState("");
  const [visitorId, setVisitorId] = useState("");
  const [status, setStatus] = useState("");
  const [rows, setRows] = useState<NotificationMessageWithAttempts[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function search() {
    if (!client) return;
    setBusy(true);
    setError(null);
    try {
      const result = await client.notifications.messages({
        ticketId: ticketId || undefined,
        visitorId: visitorId || undefined,
        status: (status || undefined) as NotificationMessageStatus | undefined,
      });
      setRows(result.items);
    } catch (cause) {
      setError(cause);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <h2 className="qms-heading">{t("notifications.log.title")}</h2>
      <div className="qms-row">
        <TextField id={`${id}-ticket`} label={t("notifications.log.ticketId")} value={ticketId} onChange={(event) => setTicketId(event.target.value)} />
        <TextField id={`${id}-visitor`} label={t("notifications.log.visitorId")} value={visitorId} onChange={(event) => setVisitorId(event.target.value)} />
        <SelectField
          id={`${id}-status`}
          label={t("notifications.log.status")}
          value={status}
          onChange={(event) => setStatus(event.target.value)}
          options={[{ value: "", label: t("notifications.log.anyStatus") }, ...STATUSES.map((value) => ({ value, label: t(`notifications.status.${value}`) }))]}
        />
        <Button type="button" disabled={busy} onClick={() => void search()}>
          {t("notifications.log.search")}
        </Button>
      </div>
      {error != null && <ErrorAlert>{describeError(t, error)}</ErrorAlert>}
      {rows && rows.length === 0 && <p className="qms-muted">{t("notifications.log.empty")}</p>}
      {rows && rows.length > 0 && (
        <ul className="qms-list">
          {rows.map(({ message }) => (
            <li key={message.id} className="qms-row">
              <span>{t(`notifications.trigger.${message.trigger_key}`)}</span>
              <span className="qms-muted">{t(`notifications.channel.${message.channel}`)}</span>
              <span className="qms-muted">{t(`notifications.status.${message.status}`)}</span>
              <span className="qms-muted">{formatTime(new Date(message.created_at))}</span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
