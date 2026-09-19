"use client";

import { ApiRequestError, type CounterSession, type TransferInput } from "@qms/api-client";
import { formatTokenNumber } from "@qms/i18n";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert } from "@qms/ui";
import { useCallback, useEffect, useRef, useState } from "react";
import { counterMovedOn, counterTopic, describeError, localisedName, queueTopic, reasonOf, waitingFrom } from "../lib/console-support";
import { useTopics } from "../lib/realtime";
import { useApi } from "../lib/runtime";
import { OpenSessionCard } from "./OpenSessionCard";
import { ServingDesk, type DeskActions } from "./ServingDesk";

/** The function keys of SRS §11.2 that this console answers (F9 break arrives with a later ticket). */
const KEYS: Record<string, keyof Pick<DeskActions, "call" | "reannounce" | "start" | "complete" | "miss" | "transfer" | "hold" | "close">> = {
  F2: "call",
  F3: "reannounce",
  F4: "start",
  F5: "complete",
  F6: "miss",
  F7: "transfer",
  F8: "hold",
  F10: "close",
};

/**
 * The agent's console (SRS §11): open a session, then call, serve and complete tickets, and close the session, all from the
 * keyboard (F2, F3, F4, F5, F6, F7, F8, F10; NFR-USA-002). The console keeps no state the server does not: on load, on coming back
 * online and after any refused or lost action it asks the server for the session again, so a refresh, a short network loss
 * or a device restart puts the agent back at the ticket they were serving (FR-AGT-004). Whether an action is allowed is
 * shown here for convenience only; the API checks each one (FR-CFG-103, FR-CFG-105).
 *
 * The console also listens to its counter and to the queue of each Service it serves (SRS §21.2), so what happens without
 * the agent's hand shows at once: the waiting counts move as visitors arrive, and the desk asks the server again when the
 * counter's session or ticket changed under it. Whether it is live or polling is the client's business, not the screen's.
 */
export function CounterConsole() {
  const { t, language } = useI18n();
  const { client } = useApi();
  /** `undefined` until the server has been asked; `null` when the agent has no live session. */
  const [session, setSession] = useState<CounterSession | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  /** Waiting tickets per Service, from the queue topics; a Service is missing until its first snapshot arrives. */
  const [waiting, setWaiting] = useState<Record<string, number>>({});
  const [outcome, setOutcome] = useState("");
  const [note, setNote] = useState("");
  /** Whether the transfer panel (F7) is open. */
  const [transferring, setTransferring] = useState(false);
  const working = useRef(false);
  /** Counts the actions begun, so a read of the session that an action overtook is thrown away rather than shown. */
  const generation = useRef(0);

  const restore = useCallback(async () => {
    if (!client) return;
    const asked = generation.current;
    try {
      const current = await client.sessions.current();
      if (asked !== generation.current) return;
      setSession(current);
      setError(null);
    } catch (cause) {
      if (asked !== generation.current) return;
      if (cause instanceof ApiRequestError && cause.code === "not_found") {
        setSession(null);
        setError(null);
      } else {
        setError(describeError(t, cause));
      }
    }
  }, [client, t]);

  useEffect(() => {
    void restore();
  }, [restore]);

  useEffect(() => {
    window.addEventListener("online", restore);
    return () => window.removeEventListener("online", restore);
  }, [restore]);

  const ticketId = session?.ticket?.id;
  const ticketState = session?.ticket?.state;
  useEffect(() => {
    setOutcome("");
    setNote("");
    // The panel is for the ticket in service; once that ticket moves on, the panel has nothing to send.
    setTransferring(false);
  }, [ticketId, ticketState]);

  /** Runs one action at a time, so a key held down or pressed twice cannot send the same action twice. */
  async function perform(action: () => Promise<void>) {
    if (working.current) return;
    working.current = true;
    generation.current += 1;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await action();
    } catch (cause) {
      const message = describeError(t, cause);
      const reason = reasonOf(cause);
      if (reason === "no_ticket_waiting") {
        setNotice(message);
      } else {
        // The server knows best: a refusal or a lost answer means our picture may be stale, so read it again.
        if (cause instanceof ApiRequestError && cause.code !== "network_error") await restore();
        setError(message);
      }
    } finally {
      working.current = false;
      setBusy(false);
    }
  }

  useTopics(session ? [counterTopic(session.counter.id)] : [], (update) => {
    // While an action is in flight its answer is the truth; the events it caused are not news.
    if (session && !working.current && counterMovedOn(update, session)) void restore();
  });
  useTopics(session ? session.services.map((service) => queueTopic(service.id)) : [], (update) => {
    const count = waitingFrom(update);
    if (count) setWaiting((counts) => (counts[count.serviceId] === count.count ? counts : { ...counts, [count.serviceId]: count.count }));
  });

  const ticket = session?.ticket ?? null;
  const canCall = session?.state === "open" && ticket === null;
  const canReannounce = ticket?.state === "called" && ticket.announce_count < ticket.announce_limit;
  const canStart = ticket?.state === "called";
  const canComplete = ticket?.state === "serving";
  const canMiss = ticket?.state === "called";
  const canTransfer = ticket?.state === "serving";
  const heldTickets = session?.held ?? [];
  const canHold = session?.state === "open" && ticket?.state === "serving" && heldTickets.length < (session?.hold_limit ?? 0);
  /** A held ticket comes back only when the desk has nothing else in progress (FR-AGT-010). */
  const canResume = session !== null && session !== undefined && session.state !== "closed" && ticket === null;
  const canClose = session?.state === "open" || session?.state === "closing";
  const actions: DeskActions = {
    canCall,
    canReannounce,
    canStart,
    canComplete,
    canMiss,
    canTransfer,
    canHold,
    canResume,
    canClose,
    call() {
      if (!client || !session || !canCall) return;
      void perform(async () => setSession(await client.sessions.next(session.id)));
    },
    reannounce() {
      if (!client || !session || !ticket || !canReannounce) return;
      void perform(async () => setSession(await client.sessions.reannounce(session.id, ticket.version)));
    },
    miss() {
      if (!client || !session || !ticket || !canMiss) return;
      void perform(async () => {
        const next = await client.sessions.miss(session.id, ticket.version);
        // Missing the ticket of a closing session resolves it, and the session closes (SRS §19.3).
        setSession(next.state === "closed" ? null : next);
      });
    },
    transfer() {
      if (!canTransfer) return;
      setTransferring((open) => !open);
    },
    hold() {
      if (!client || !session || !ticket || !canHold) return;
      void perform(async () => setSession(await client.sessions.hold(session.id, ticket.version)));
    },
    resume(held) {
      if (!client || !session || !canResume) return;
      void perform(async () => setSession(await client.sessions.resume(session.id, held.id, held.version)));
    },
    start() {
      if (!client || !session || !ticket || !canStart) return;
      void perform(async () => setSession(await client.sessions.serve(session.id, ticket.version)));
    },
    complete() {
      if (!client || !session || !ticket || !canComplete) return;
      if (ticket.outcomes.length > 0 && outcome === "") {
        setError(t("console.outcome.required"));
        document.getElementById("console-outcome")?.focus();
        return;
      }
      void perform(async () => {
        const next = await client.sessions.complete(
          session.id,
          { ...(outcome === "" ? {} : { outcome_code_id: outcome }), ...(note.trim() === "" ? {} : { note: note.trim() }) },
          ticket.version,
        );
        // Completing the ticket of a closing session closes it (SRS §19.3).
        setSession(next.state === "closed" ? null : next);
      });
    },
    close() {
      if (!client || !session || !canClose) return;
      void perform(async () => {
        await client.sessions.close(session.id);
        setSession(null);
      });
    },
  };

  /** Sends the ticket in service to its target (FR-QUE-052); the answer carries the session as it stands, and the successor's place. */
  function transfer(input: TransferInput) {
    if (!client || !session || !ticket || !canTransfer) return;
    void perform(async () => {
      const result = await client.tickets.transfer(ticket.id, input, ticket.version);
      // Transferring the ticket of a closing session closes it (SRS §19.3).
      setSession(result.session.state === "closed" ? null : result.session);
      setTransferring(false);
      const service = localisedName(result.successor.service.name_i18n, language);
      setNotice(t("console.transfer.done", { token: formatTokenNumber(result.successor.token_number), service }));
    });
  }

  // The keys act on the latest render's actions without re-registering the listener on every render.
  const latest = useRef(actions);
  latest.current = actions;
  const openPanel = useRef(false);
  openPanel.current = transferring;
  const active = Boolean(session);
  useEffect(() => {
    if (!active) return;
    function onKeyDown(event: KeyboardEvent) {
      const action = KEYS[event.key];
      if (!action) return;
      // F5 reloads the page and F10 opens a menu in some browsers; here they are the agent's actions.
      event.preventDefault();
      if (event.repeat) return;
      // While the transfer panel is open the keys are the panel's: typing a note must not complete the ticket. F7 closes it again.
      if (openPanel.current && action !== "transfer") return;
      latest.current[action]();
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [active]);

  if (session === undefined && error === null) return <p className="qms-muted">{t("common.loading")}</p>;

  return (
    <div className="qms-stack">
      {error !== null && (
        <>
          <ErrorAlert>{error}</ErrorAlert>
          <div>
            <Button variant="secondary" type="button" onClick={() => void restore()}>
              {t("common.retry")}
            </Button>
          </div>
        </>
      )}
      {notice !== null && <p role="status">{notice}</p>}
      {session === null && <OpenSessionCard onOpened={setSession} />}
      {session && (
        <ServingDesk
          session={session}
          actions={actions}
          busy={busy}
          waiting={waiting}
          outcome={outcome}
          onOutcome={setOutcome}
          note={note}
          onNote={setNote}
          transferring={transferring}
          onTransfer={transfer}
          onCancelTransfer={() => setTransferring(false)}
        />
      )}
    </div>
  );
}
