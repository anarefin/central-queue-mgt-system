import { ApiRequestError, type CounterSession } from "@qms/api-client";
import type { RealtimeUpdate } from "@qms/realtime-client";
import type { I18n } from "@qms/i18n";

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
  if (update.type === "session.closed") return update.data.session_id === session.id;
  const state = update.data.state;
  const ticketId = update.data.ticket_id;
  if (state === "called" && shown && shown.id === ticketId && shown.state === "called") {
    // A Re-announce made elsewhere leaves the ticket where it is but moves its announce count (FR-QUE-083).
    const count = update.data.announce_count;
    return typeof count === "number" && count !== shown.announce_count;
  }
  if (state === "held") return !session.held.some((held) => held.id === ticketId); // parked elsewhere: the held list is behind
  if (state === "called" || state === "serving") return shown?.id !== ticketId || shown?.state !== state;
  // The ticket left service (completed, missed, transferred…) but the screen still holds it, in service or held (FR-AGT-013).
  return shown?.id === ticketId || session.held.some((held) => held.id === ticketId);
}
