"use client";

import type { CounterSession, QueueSnapshot } from "@qms/api-client";
import { formatTokenNumber } from "@qms/i18n";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField } from "@qms/ui";
import { useEffect, useState, type FormEvent } from "react";
import { describeError, localisedName } from "../lib/console-support";
import { useApi } from "../lib/runtime";

/** How many of a queue's tickets the panel lists; the agent picks one of the first few, not the hundredth. */
const LISTED = 20;

interface Props {
  session: CounterSession;
  busy: boolean;
  onSubmit: (ticketId: string, reason: string) => void;
  onCancel: () => void;
}

/**
 * Call a specific waiting ticket out of order (FR-AGT-012): choose one of the Services the session serves, choose a ticket from its
 * queue, and give the reason, which is required and is audited with the call (FR-SEC-040). The tickets are the ones the API lists for
 * that queue; the API checks the choice again. Esc closes the panel without calling anything.
 */
export function CallTicketPanel({ session, busy, onSubmit, onCancel }: Props) {
  const { t, language, formatNumber } = useI18n();
  const { client } = useApi();
  const [serviceId, setServiceId] = useState(session.services[0]?.id ?? "");
  const [queue, setQueue] = useState<QueueSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [ticketId, setTicketId] = useState("");
  const [reason, setReason] = useState("");

  useEffect(() => {
    if (!client || serviceId === "") return;
    let current = true;
    setQueue(null);
    setTicketId("");
    client.queues
      .snapshot(serviceId, LISTED)
      .then((loaded) => current && (setQueue(loaded), setError(null)))
      .catch((cause) => current && setError(describeError(t, cause)));
    return () => {
      current = false;
    };
  }, [client, serviceId, t]);

  useEffect(() => {
    if (queue) document.getElementById("call-ticket")?.focus();
  }, [queue]);

  const waiting = queue?.tickets.filter((ticket) => ticket.state === "waiting") ?? [];
  const ready = ticketId !== "" && reason.trim() !== "";

  function submit(event: FormEvent) {
    event.preventDefault();
    if (ready) onSubmit(ticketId, reason.trim());
  }

  return (
    <Card>
      <form
        className="qms-stack"
        onSubmit={submit}
        onKeyDown={(event) => {
          if (event.key === "Escape") onCancel();
        }}
      >
        <h3 className="qms-heading">{t("console.callTicket.title")}</h3>
        {error !== null && <ErrorAlert>{error}</ErrorAlert>}
        <SelectField
          id="call-service"
          label={t("console.callTicket.service")}
          value={serviceId}
          onChange={(event) => setServiceId(event.target.value)}
          options={session.services.map((s) => ({ value: s.id, label: localisedName(s.name_i18n, language) }))}
        />
        {queue === null && error === null && <p className="qms-muted">{t("console.callTicket.loading")}</p>}
        {queue && waiting.length === 0 && <p className="qms-muted">{t("console.callTicket.empty")}</p>}
        {queue && waiting.length > 0 && (
          <SelectField
            id="call-ticket"
            label={t("console.callTicket.ticket")}
            value={ticketId}
            onChange={(event) => setTicketId(event.target.value)}
            options={[
              { value: "", label: t("console.callTicket.choose") },
              ...waiting.map((ticket) => ({
                value: ticket.id,
                label: t("console.callTicket.option", {
                  position: formatNumber(ticket.position),
                  token: formatTokenNumber(ticket.token_number),
                  class: ticket.priority_class ? localisedName(ticket.priority_class.name_i18n, language) : "",
                }).trim(),
              })),
            ]}
          />
        )}
        <div>
          <label className="qms-label" htmlFor="call-reason">
            {t("console.callTicket.reason")}
          </label>
          <input className="qms-input" id="call-reason" value={reason} maxLength={1000} required onChange={(event) => setReason(event.target.value)} />
        </div>
        <div className="qms-row">
          <Button type="submit" disabled={!ready || busy}>
            {t("console.callTicket.submit")}
          </Button>
          <Button type="button" variant="secondary" onClick={onCancel}>
            {t("console.callTicket.cancel")}
          </Button>
        </div>
      </form>
    </Card>
  );
}
