"use client";

import type { SessionTicket, TransferInput, TransferTargets } from "@qms/api-client";
import { formatTokenNumber } from "@qms/i18n";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert, SelectField, SidePanel, TextField } from "@qms/ui";
import { useEffect, useState, type FormEvent } from "react";
import { describeError, localisedName } from "../lib/console-support";
import { useApi } from "../lib/runtime";

type Who = "anyone" | "counter" | "agent";

interface Props {
  sessionId: string;
  ticket: SessionTicket;
  busy: boolean;
  onSubmit: (input: TransferInput) => void;
  onCancel: () => void;
}

/**
 * Transfer (F7, SRS §11.2, FR-QUE-052): send the ticket in service to another Service, or to one of its counters or agents, with a
 * note the next agent will read. The note is required. The options are the ones the API lists for this session's site, so nothing
 * from another site is offered (ADR-0002); the API checks the choice again. A ticket sent to an agent waits in that agent's personal
 * queue (FR-QUE-003). Esc closes the panel without sending anything.
 */
export function TransferPanel({ sessionId, ticket, busy, onSubmit, onCancel }: Props) {
  const { t, language } = useI18n();
  const { client } = useApi();
  const [targets, setTargets] = useState<TransferTargets | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [serviceId, setServiceId] = useState("");
  const [who, setWho] = useState<Who>("anyone");
  const [counterId, setCounterId] = useState("");
  const [agentId, setAgentId] = useState("");
  const [note, setNote] = useState("");

  useEffect(() => {
    if (!client) return;
    let current = true;
    client.sessions
      .transferTargets(sessionId)
      .then((loaded) => current && setTargets(loaded))
      .catch((cause) => current && setError(describeError(t, cause)));
    return () => {
      current = false;
    };
  }, [client, sessionId, t]);

  useEffect(() => {
    if (targets) document.getElementById("transfer-service")?.focus();
  }, [targets]);

  const counters = targets?.counters.filter((c) => c.service_ids.includes(serviceId)) ?? [];
  const agents = targets?.agents.filter((a) => a.service_ids.includes(serviceId)) ?? [];
  const sameService = serviceId === ticket.service.id && who === "anyone";
  const narrowed = who === "anyone" || (who === "counter" ? counterId !== "" : agentId !== "");
  const ready = serviceId !== "" && narrowed && !sameService && note.trim() !== "";

  function chooseService(id: string) {
    setServiceId(id);
    setCounterId("");
    setAgentId("");
  }

  function chooseWho(next: Who) {
    setWho(next);
    setCounterId("");
    setAgentId("");
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!ready) return;
    onSubmit({
      service_id: serviceId,
      ...(who === "counter" ? { counter_id: counterId } : {}),
      ...(who === "agent" ? { agent_id: agentId } : {}),
      note: note.trim(),
    });
  }

  return (
    <SidePanel label={t("console.transfer.title", { token: formatTokenNumber(ticket.token_number) })} onClose={onCancel}>
      <form className="flex flex-col gap-4" onSubmit={submit}>
        <h3 className="text-lg font-semibold text-fg">{t("console.transfer.title", { token: formatTokenNumber(ticket.token_number) })}</h3>
        {error !== null && <ErrorAlert>{error}</ErrorAlert>}
        {targets === null && error === null && <p className="text-fg-muted">{t("console.transfer.loading")}</p>}
        {targets && (
          <>
            <SelectField
              id="transfer-service"
              label={t("console.transfer.service")}
              value={serviceId}
              onChange={(event) => chooseService(event.target.value)}
              options={[
                { value: "", label: t("console.transfer.serviceChoose") },
                ...targets.services.map((s) => ({ value: s.id, label: localisedName(s.name_i18n, language) })),
              ]}
            />
            <fieldset className="flex flex-col gap-2">
              <legend className="text-sm font-medium text-fg">{t("console.transfer.who")}</legend>
              {(["anyone", "counter", "agent"] as const).map((option) => (
                <label key={option} className="flex items-center gap-2 text-sm text-fg">
                  <input type="radio" name="transfer-who" value={option} checked={who === option} onChange={() => chooseWho(option)} /> {t(`console.transfer.${option}`)}
                </label>
              ))}
            </fieldset>
            {who === "counter" && (
              <SelectField
                id="transfer-counter"
                label={t("console.transfer.counterLabel")}
                value={counterId}
                onChange={(event) => setCounterId(event.target.value)}
                options={[
                  { value: "", label: t("console.transfer.choose") },
                  ...counters.map((c) => ({ value: c.id, label: t("console.open.counterOption", { label: c.label, zone: c.zone_name }) })),
                ]}
              />
            )}
            {who === "agent" && (
              <SelectField
                id="transfer-agent"
                label={t("console.transfer.agentLabel")}
                value={agentId}
                onChange={(event) => setAgentId(event.target.value)}
                options={[{ value: "", label: t("console.transfer.choose") }, ...agents.map((a) => ({ value: a.id, label: a.name }))]}
              />
            )}
            {serviceId !== "" && ((who === "counter" && counters.length === 0) || (who === "agent" && agents.length === 0)) && (
              <p className="text-fg-muted">{t("console.transfer.none")}</p>
            )}
            {sameService && serviceId !== "" && <p className="text-fg-muted">{t("console.transfer.sameService")}</p>}
            <TextField id="transfer-note" label={t("console.transfer.note")} value={note} maxLength={1000} required onChange={(event) => setNote(event.target.value)} />
          </>
        )}
        <div className="flex flex-wrap items-center gap-3">
          <Button type="submit" disabled={!ready || busy}>
            {t("console.transfer.submit")}
          </Button>
          <Button type="button" variant="secondary" onClick={onCancel}>
            {t("console.transfer.cancel")}
          </Button>
        </div>
      </form>
    </SidePanel>
  );
}
