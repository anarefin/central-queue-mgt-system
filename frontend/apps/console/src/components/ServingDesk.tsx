"use client";

import type { CounterSession, SessionTicket, TransferInput } from "@qms/api-client";
import { formatTokenNumber } from "@qms/i18n";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, SelectField } from "@qms/ui";
import { useEffect } from "react";
import { localisedName } from "../lib/console-support";
import { BreakCard } from "./BreakCard";
import { BreakPanel } from "./BreakPanel";
import { CallTicketPanel } from "./CallTicketPanel";
import { TransferPanel } from "./TransferPanel";

/** What the desk lets the agent do right now; the server checks every one of these again (FR-CFG-103). */
export interface DeskActions {
  canCall: boolean;
  canReannounce: boolean;
  canStart: boolean;
  canComplete: boolean;
  canMiss: boolean;
  canTransfer: boolean;
  canHold: boolean;
  canResume: boolean;
  canClose: boolean;
  /** Call a specific waiting ticket out of order, with a reason (FR-AGT-012). */
  canCallSpecific: boolean;
  /** Return a called ticket that has waited for its Agent past the call timeout to the queue (FR-QUE-032). */
  canReturn: boolean;
  /** F9 with the session open and nothing in progress: offers the break types. */
  canBreak: boolean;
  /** F9 while on a break: ends it. */
  canEndBreak: boolean;
  call: () => void;
  reannounce: () => void;
  start: () => void;
  complete: () => void;
  miss: () => void;
  /** F7: opens the transfer panel, or closes it when it is already open. */
  transfer: () => void;
  hold: () => void;
  /** F9: ends the break when on one, otherwise opens or closes the break panel. */
  takeBreak: () => void;
  resume: (held: SessionTicket) => void;
  close: () => void;
  /** Opens the out-of-order call panel, or closes it when it is already open. */
  callSpecific: () => void;
  returnToQueue: () => void;
}

interface Props {
  session: CounterSession;
  /** The ticket in progress the actions are for: the one chosen from the list, else the first (FR-AGT-011). */
  ticket: SessionTicket | null;
  onSelect: (ticketId: string) => void;
  /** The call timeout has passed for the ticket and the agent has not yet chosen to keep it (FR-QUE-032). */
  timedOut: boolean;
  onKeep: () => void;
  actions: DeskActions;
  busy: boolean;
  /** Tickets waiting per Service id, as the queue topics last said; a Service with no entry has not been heard from yet. */
  waiting: Record<string, number>;
  outcome: string;
  onOutcome: (id: string) => void;
  note: string;
  onNote: (note: string) => void;
  /** Whether the transfer panel (F7) is open, and what it sends. */
  transferring: boolean;
  onTransfer: (input: TransferInput) => void;
  onCancelTransfer: () => void;
  /** Whether the break panel (F9) is open, and what it sends. */
  breaking: boolean;
  onStartBreak: (breakTypeId: string) => void;
  onCancelBreak: () => void;
  /** Whether the out-of-order call panel is open, and what it sends. */
  callingSpecific: boolean;
  onCallSpecific: (ticketId: string, reason: string) => void;
  onCancelCallSpecific: () => void;
}

/**
 * The counter desk (SRS §11.2): the ticket in progress and its actions, each on a function key so a whole day's work needs
 * no mouse (NFR-USA-002). Call next is off while a ticket is called or serving (FR-AGT-010). A called ticket can be
 * re-announced (F3, up to the limit) or missed (F6); the desk says so beforehand when the next Miss would close it as a
 * no-show, since that cannot be undone (FR-QUE-050). A ticket in service can be transferred (F7) to another Service, counter or agent
 * with a note (FR-QUE-052), or held (F8) so the counter can call the next one;
 * the held tickets are listed here and each is resumed from the list, and they must all be cleared before the session can close
 * (FR-AGT-013). A Service served in parallel lets the counter have several tickets in progress (FR-AGT-011): they are listed, one is chosen,
 * and the actions are for it. A called ticket that its agent has not acted on for the call timeout says so, and may be returned to the
 * queue with its original wait or kept (FR-QUE-032). A specific waiting ticket can be called out of order with a reason (FR-AGT-012). F9 takes a break of a chosen type when nothing is in progress: while it runs no ticket is assigned and the desk shows its
 * clock (FR-AGT-021, FR-AGT-022); F9 again ends it. The outcome
 * takes focus when service starts, so the keys, an arrow and F5 are all it takes to finish a ticket.
 */
export function ServingDesk({
  session,
  ticket,
  onSelect,
  timedOut,
  onKeep,
  actions,
  busy,
  waiting,
  outcome,
  onOutcome,
  note,
  onNote,
  transferring,
  onTransfer,
  onCancelTransfer,
  breaking,
  onStartBreak,
  onCancelBreak,
  callingSpecific,
  onCallSpecific,
  onCancelCallSpecific,
}: Props) {
  const { t, language, formatNumber } = useI18n();
  const serving = ticket?.state === "serving";
  const held = session.held;

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

      {session.tickets.length > 1 && (
        <Card>
          <h3 className="qms-label">{t("console.inProgress.title", { count: formatNumber(session.tickets.length) })}</h3>
          <ul className="qms-list" aria-label={t("console.inProgress.list")}>
            {session.tickets.map((inProgress) => (
              <li key={inProgress.id} className="qms-row" data-testid={`in-progress-${inProgress.id}`}>
                <span>
                  <span className="qms-token">{formatTokenNumber(inProgress.token_number)}</span>{" "}
                  <span className="qms-muted">
                    {localisedName(inProgress.service.name_i18n, language)} · {t(`console.ticket.state.${inProgress.state}`)}
                  </span>
                </span>
                <Button
                  type="button"
                  variant="secondary"
                  aria-pressed={inProgress.id === ticket?.id}
                  disabled={busy || inProgress.id === ticket?.id}
                  onClick={() => onSelect(inProgress.id)}
                  aria-label={t("console.inProgress.select", { token: inProgress.token_number })}
                >
                  {t("console.inProgress.selectShort")}
                </Button>
              </li>
            ))}
          </ul>
        </Card>
      )}

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

      {ticket && timedOut && (
        <Card>
          <h3 className="qms-label">{t("console.timeout.title")}</h3>
          <p className="qms-warning" data-testid="call-timeout">
            {t("console.timeout.prompt", { token: formatTokenNumber(ticket.token_number), seconds: formatNumber(session.call_timeout_seconds) })}
          </p>
          <div className="qms-row">
            <Button type="button" disabled={!actions.canReturn || busy} onClick={actions.returnToQueue}>
              {t("console.timeout.return")}
            </Button>
            <Button type="button" variant="secondary" disabled={busy} onClick={onKeep}>
              {t("console.timeout.keep")}
            </Button>
          </div>
        </Card>
      )}

      <BreakCard session={session} busy={busy} canEnd={actions.canEndBreak} onEnd={actions.takeBreak} />
      {breaking && session.state === "open" && <BreakPanel busy={busy} onSubmit={onStartBreak} onCancel={onCancelBreak} />}

      {callingSpecific && session.state === "open" && <CallTicketPanel session={session} busy={busy} onSubmit={onCallSpecific} onCancel={onCancelCallSpecific} />}

      {transferring && ticket && serving && <TransferPanel sessionId={session.id} ticket={ticket} busy={busy} onSubmit={onTransfer} onCancel={onCancelTransfer} />}

      {held.length > 0 && (
        <Card>
          <h3 className="qms-label">{t("console.held.title", { count: formatNumber(held.length), limit: formatNumber(session.hold_limit) })}</h3>
          <ul className="qms-list" aria-label={t("console.held.list")}>
            {held.map((heldTicket) => (
              <li key={heldTicket.id} className="qms-row" data-testid={`held-${heldTicket.id}`}>
                <span>
                  <span className="qms-token">{formatTokenNumber(heldTicket.token_number)}</span>{" "}
                  <span className="qms-muted">{localisedName(heldTicket.service.name_i18n, language)}</span>
                </span>
                <Button
                  type="button"
                  variant="secondary"
                  disabled={!actions.canResume || busy}
                  onClick={() => actions.resume(heldTicket)}
                  aria-label={t("console.held.resumeFor", { token: heldTicket.token_number })}
                >
                  {t("console.held.resume")}
                </Button>
              </li>
            ))}
          </ul>
          {session.state === "closing" && <p className="qms-warning">{t("console.held.closing")}</p>}
        </Card>
      )}

      <Card>
        <div className="qms-row">
          {button(t("console.action.next"), "F2", actions.canCall, actions.call)}
          <Button type="button" variant="secondary" disabled={!actions.canCallSpecific || busy} onClick={actions.callSpecific}>
            {t("console.action.callTicket")}
          </Button>
          {button(t("console.action.reannounce"), "F3", actions.canReannounce, actions.reannounce, "secondary")}
          {button(t("console.action.serve"), "F4", actions.canStart, actions.start)}
          {button(t("console.action.complete"), "F5", actions.canComplete, actions.complete)}
          {button(t("console.action.miss"), "F6", actions.canMiss, actions.miss, "secondary")}
          {button(t("console.action.transfer"), "F7", actions.canTransfer, actions.transfer, "secondary")}
          {button(t("console.action.hold"), "F8", actions.canHold, actions.hold, "secondary")}
          {button(session.state === "on_break" ? t("console.action.endBreak") : t("console.action.break"), "F9", actions.canBreak || actions.canEndBreak, actions.takeBreak, "secondary")}
          {button(t("console.action.close"), "F10", actions.canClose, actions.close, "secondary")}
        </div>
        <p className="qms-muted">{t("console.shortcuts")}</p>
      </Card>
    </div>
  );
}
