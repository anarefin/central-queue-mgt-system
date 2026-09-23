import { ApiRequestError, type CounterSession, type Items } from "@qms/api-client";
import type { RealtimeUpdate } from "@qms/realtime-client";
import type { I18n } from "@qms/i18n";
import { useCallback, useEffect, useState } from "react";

/** Reasons the API gives a refused session action that the console has a sentence for. */
const REFUSALS = new Set([
  "no_ticket_waiting",
  "ticket_in_progress",
  "no_ticket_called",
  "no_ticket_serving",
  "reannounce_limit_reached",
  "hold_limit_reached",
  "held_tickets_remaining",
  "no_ticket_held",
  "version_mismatch",
  "session_not_open",
  "counter_occupied",
  "agent_has_open_session",
  "counter_inactive",
  "transfer_target_inactive",
  "transfer_cross_site",
  "transfer_target_mismatch",
  "already_on_break",
  "not_on_break",
  "ticket_not_callable",
  "call_not_timed_out",
]);

/** A localised sentence for a failed call; the code and reason, never the server's text, choose it (SRS §20.3). */
export function describeError(t: I18n["t"], cause: unknown): string {
  if (!(cause instanceof ApiRequestError)) return t("errors.network_error");
  const reason = cause.body?.details?.reason;
  if (cause.code === "conflict" && typeof reason === "string" && REFUSALS.has(reason)) return t(`console.refused.${reason}`);
  return t(`errors.${cause.code}`);
}

/** The reason a 409 gives, when it names one. */
export function reasonOf(cause: unknown): string | undefined {
  const reason = cause instanceof ApiRequestError ? cause.body?.details?.reason : undefined;
  return typeof reason === "string" ? reason : undefined;
}

/** A name in the reader's language, falling back to English and then to whatever there is, never to nothing (FR-I18N-011). */
export function localisedName(names: Record<string, string>, language: string): string {
  return names[language] ?? names.en ?? Object.values(names)[0] ?? "";
}

const QUEUE_PREFIX = "queue:";

export const queueTopic = (serviceId: string): string => `${QUEUE_PREFIX}${serviceId}`;
export const counterTopic = (counterId: string): string => `counter:${counterId}`;

/** The waiting count a queue topic reports, keyed by its Service, or null when the update says nothing about it. */
export function waitingFrom(update: RealtimeUpdate): { serviceId: string; count: number } | null {
  if (update.kind === "denied" || !update.topic.startsWith(QUEUE_PREFIX)) return null;
  const count = update.data.waiting_count;
  return typeof count === "number" ? { serviceId: update.topic.slice(QUEUE_PREFIX.length), count } : null;
}

/**
 * The wait estimate a queue topic reports, keyed by its Service: the rounded range a ticket issued now would be given
 * (FR-QUE-042). Null when the update says nothing about it, such as an event that carries only a count.
 */
export function estimateFrom(update: RealtimeUpdate): { serviceId: string; low: number; high: number } | null {
  if (update.kind === "denied" || !update.topic.startsWith(QUEUE_PREFIX)) return null;
  const range = update.data.estimated_wait_minutes;
  if (typeof range !== "object" || range === null) return null;
  const { low, high } = range as { low?: unknown; high?: unknown };
  return typeof low === "number" && typeof high === "number" ? { serviceId: update.topic.slice(QUEUE_PREFIX.length), low, high } : null;
}

/**
 * Whether a counter-topic update says the session on screen is behind the server (FR-AGT-004: the server is the truth).
 * A snapshot is compared with the session and the ticket shown; an event is the echo of what the screen already shows
 * when the agent's own action has been answered, so only the rest send the console back to ask. A resync always does.
 */
export function counterMovedOn(update: RealtimeUpdate, session: CounterSession): boolean {
  if (update.kind === "denied") return false;
  const shown = session.ticket;
  if (update.kind === "snapshot") {
    if (update.resync) return true;
    const live = update.data.session as { id?: string; state?: string } | null | undefined;
    const ticket = update.data.ticket as { id?: string; state?: string; version?: number } | null | undefined;
    if (!live || live.id !== session.id || live.state !== session.state) return true;
    return (ticket?.id ?? null) !== (shown?.id ?? null) || (ticket?.state ?? null) !== (shown?.state ?? null) || (ticket?.version ?? null) !== (shown?.version ?? null);
  }
  if (update.type === "session.opened") return update.data.session_id !== session.id;
  // The Agent has not acted on a call for the call timeout: the screen is behind until it shows the prompt (FR-QUE-032).
  if (update.type === "ticket.call_timeout") return session.tickets.some((t) => t.id === update.data.ticket_id && !t.call_timed_out);
  if (update.type === "session.closed") return update.data.session_id === session.id;
  // A break started or ended by someone else (an admin sets availability, FR-AGT-024) leaves the screen behind the server.
  if (update.type === "session.break_started" || update.type === "session.break_ended") return update.data.session_id === session.id && update.data.state !== session.state;
  const state = update.data.state;
  const ticketId = update.data.ticket_id;
  // With parallel serving several tickets are in progress at once (FR-AGT-011); this one is among them or it is not.
  const inProgress = session.tickets.find((t) => t.id === ticketId);
  if (state === "called" && inProgress?.state === "called") {
    // A Re-announce made elsewhere leaves the ticket where it is but moves its announce count (FR-QUE-083).
    const count = update.data.announce_count;
    return typeof count === "number" && count !== inProgress.announce_count;
  }
  if (state === "held") return !session.held.some((held) => held.id === ticketId); // parked elsewhere: the held list is behind
  if (state === "called" || state === "serving") return inProgress?.state !== state;
  // The ticket left service (completed, missed, transferred…) but the screen still holds it, in service or held (FR-AGT-013).
  return inProgress !== undefined || session.held.some((held) => held.id === ticketId);
}

/**
 * Whole seconds since {@code startedAt}, moving each second, never negative (a device clock a little behind the
 * server's) — the break clock `BreakCard` shows the agent, and the same figure the app shell's top bar shows
 * (ticket 64, FR-AGT-021, FR-AGT-022).
 */
export function useElapsedSeconds(startedAt: string | null | undefined): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!startedAt) return;
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [startedAt]);
  return startedAt ? Math.max(0, Math.floor((now - Date.parse(startedAt)) / 1000)) : 0;
}

/**
 * Loads a list through the api-client once `load` is ready, the same shape `apps/admin`'s own `useList` already
 * uses (ticket 63): the live dashboard's pickers load their options on demand (ticket 64, FR-MON-002) and this is
 * the one place that does it, so every picker's loading state, error and reload behave alike.
 */
export function useList<T>(load: (() => Promise<Items<T>>) | null, deps: readonly unknown[]) {
  const [items, setItems] = useState<T[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    if (!load) return;
    let cancelled = false;
    setError(null);
    load().then(
      (result) => !cancelled && setItems(result.items),
      (cause: unknown) => !cancelled && setError(cause),
    );
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, version]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);
  return { items, error, reload };
}

/**
 * A list loaded only once {@link request} is first called (ticket 64: the live dashboard's pickers load their
 * options on demand, not before the agent opens one), and reloaded the next time it is requested after `deps`
 * changes — a chosen site's zones stop being right once another site is chosen, for instance.
 */
export function useOnDemandList<T>(load: () => Promise<Items<T>>, deps: readonly unknown[]) {
  const [items, setItems] = useState<T[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [requested, setRequested] = useState(false);

  useEffect(() => {
    setItems(null);
    setError(null);
    setRequested(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => {
    if (!requested) return;
    let cancelled = false;
    load().then(
      (result) => !cancelled && setItems(result.items),
      (cause: unknown) => !cancelled && setError(cause),
    );
    return () => {
      cancelled = true;
    };
    // Deps deliberately excludes `deps` and `load`: the reset effect above already clears `requested` to false
    // whenever `deps` changes, so this only re-fires from that (`requested` flips) or from a fresh `request()`
    // call once `deps` has settled — either way it runs after the reset, so it always sees the current `load`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requested]);

  const request = useCallback(() => setRequested(true), []);
  return { items, error, loading: requested && items === null && error === null, request };
}
