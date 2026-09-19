"use client";

import type { CounterSession } from "@qms/api-client";
import { formatTokenNumber } from "@qms/i18n";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, SelectField } from "@qms/ui";
import { useEffect } from "react";
import { localisedName } from "../lib/console-support";

/** What the desk lets the agent do right now; the server checks every one of these again (FR-CFG-103). */
export interface DeskActions {
  canCall: boolean;
  canReannounce: boolean;
  canStart: boolean;
  canComplete: boolean;
  canMiss: boolean;
  canClose: boolean;
  call: () => void;
  reannounce: () => void;
  start: () => void;
  complete: () => void;
  miss: () => void;
  close: () => void;
}

interface Props {
  session: CounterSession;
  actions: DeskActions;
  busy: boolean;
  /** Tickets waiting per Service id, as the queue topics last said; a Service with no entry has not been heard from yet. */
  waiting: Record<string, number>;
  outcome: string;
  onOutcome: (id: string) => void;
  note: string;
  onNote: (note: string) => void;
}

/**
 * The counter desk (SRS §11.2): the ticket in progress and its actions, each on a function key so a whole day's work needs
 * no mouse (NFR-USA-002). Call next is off while a ticket is called or serving (FR-AGT-010). A called ticket can be
 * re-announced (F3, up to the limit) or missed (F6); the desk says so beforehand when the next Miss would close it as a
 * no-show, since that cannot be undone (FR-QUE-050). The outcome
 * takes focus when service starts, so the keys, an arrow and F5 are all it takes to finish a ticket.
 */
export function ServingDesk({ session, actions, busy, waiting, outcome, onOutcome, note, onNote }: Props) {
  const { t, language, formatNumber } = useI18n();
  const ticket = session.ticket;
  const serving = ticket?.state === "serving";

  useEffect(() => {
    if (serving) document.getElementById("console-outcome")?.focus();
  }, [serving, ticket?.id]);

  const waited = ticket?.wait_seconds ?? 0;
  const button = (label: string, key: string, enabled: boolean, run: () => void, variant?: "secondary") => (
    <Button type="button" variant={variant} disabled={!enabled || busy} onClick={run} aria-keyshortcuts={key}>
      {label} <kbd>{key}</kbd>
    </Button>
  );

  return (
    <div className="qms-stack">
      <Card>
        <div className="qms-row">
          <h2 className="qms-heading">{t("console.session.counter", { label: session.counter.label })}</h2>
          <span className="qms-muted">{t(`console.session.state.${session.state}`)}</span>
        </div>
        <p className="qms-muted">
          {t("console.session.services", { services: session.services.map((s) => localisedName(s.name_i18n, language)).join(", ") })}
        </p>
        {session.state === "closing" && <p className="qms-warning">{t("console.session.closing")}</p>}
      </Card>

      <Card>
        <h3 className="qms-label">{t("console.waiting.title")}</h3>
        <ul className="qms-list" aria-label={t("console.waiting.title")}>
          {session.services.map((service) => {
            const count = waiting[service.id];
            return (
              <li key={service.id} data-testid={`waiting-${service.id}`}>
                {t("console.waiting.row", { service: localisedName(service.name_i18n, language), count: count === undefined ? "–" : formatNumber(count) })}
              </li>
            );
          })}
        </ul>
      </Card>

      <Card>
        {!ticket && <p className="qms-muted">{t("console.ticket.none")}</p>}
        {ticket && (
          <>
            <p className="qms-muted">{t(`console.ticket.state.${ticket.state}`)}</p>
            <p className="qms-heading qms-token" data-testid="current-token">
              {formatTokenNumber(ticket.token_number)}
            </p>
            <p>{t("console.ticket.service", { service: localisedName(ticket.service.name_i18n, language) })}</p>
            <p className="qms-muted">
              {t("console.ticket.channel", { channel: t(`catalogue.channel.${ticket.origin_channel}`) })} ·{" "}
              {t("console.ticket.waited", { minutes: formatNumber(Math.floor(waited / 60)), seconds: formatNumber(waited % 60) })}
            </p>
            {ticket.state === "called" && ticket.announce_count > 0 && (
              <p className="qms-muted">
                {t("console.ticket.announced", { count: formatNumber(ticket.announce_count), limit: formatNumber(ticket.announce_limit) })}
              </p>
            )}
            {ticket.state === "called" && ticket.miss_count >= ticket.miss_limit && (
              <p className="qms-warning">{t("console.ticket.missLast", { count: formatNumber(ticket.miss_count), limit: formatNumber(ticket.miss_limit) })}</p>
            )}
            {serving && (
              <>
                {ticket.outcomes.length > 0 && (
                  <SelectField
                    id="console-outcome"
                    label={t("console.outcome.label")}
                    value={outcome}
                    onChange={(event) => onOutcome(event.target.value)}
                    options={[
                      { value: "", label: t("console.outcome.choose") },
                      ...ticket.outcomes.map((o) => ({ value: o.id, label: localisedName(o.label_i18n, language) })),
                    ]}
                  />
                )}
                <div>
                  <label className="qms-label" htmlFor="console-note">
                    {t("console.note.label")}
                  </label>
                  <input className="qms-input" id="console-note" value={note} maxLength={1000} onChange={(event) => onNote(event.target.value)} />
                </div>
              </>
            )}
          </>
        )}
      </Card>

      <Card>
        <div className="qms-row">
          {button(t("console.action.next"), "F2", actions.canCall, actions.call)}
          {button(t("console.action.reannounce"), "F3", actions.canReannounce, actions.reannounce, "secondary")}
          {button(t("console.action.serve"), "F4", actions.canStart, actions.start)}
          {button(t("console.action.complete"), "F5", actions.canComplete, actions.complete)}
          {button(t("console.action.miss"), "F6", actions.canMiss, actions.miss, "secondary")}
          {button(t("console.action.close"), "F10", actions.canClose, actions.close, "secondary")}
        </div>
        <p className="qms-muted">{t("console.shortcuts")}</p>
      </Card>
    </div>
  );
}
