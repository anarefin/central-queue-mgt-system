import type { Channel, LocalisedText } from "./catalogue";

/** Counter sessions and the serving actions inside them (SRS §11, §19.3, §20.4). */
export type SessionState = "open" | "on_break" | "closing" | "closed" | "force_closed";

export interface SessionCounter {
  id: string;
  label: string;
  zone_id: string;
  zone_name: string;
  site_id: string;
}

/** A Service a counter serves; weight 1 is the primary link, a higher weight a fallback (FR-CFG-011). */
export interface SessionService {
  id: string;
  name_i18n: LocalisedText;
  preference_weight: number;
}

/** One outcome an agent may record when completing the ticket's Service (FR-AGT-032). */
export interface SessionOutcome {
  id: string;
  code: string;
  label_i18n: LocalisedText;
}

/**
 * A ticket bound to the session: the one in progress (`called` or `serving`) or one of those the agent holds (`held`, FR-AGT-013).
 * `version` is what goes back as `If-Match` (SRS §20.1).
 */
export interface SessionTicket {
  id: string;
  /** Always Western Arabic digits (FR-I18N-020); show it as is. */
  token_number: string;
  state: "called" | "serving" | "held";
  version: number;
  service: { id: string; name_i18n: LocalisedText };
  origin_channel: Channel;
  priority_class: { id: string; name_i18n: LocalisedText } | null;
  queued_at: string;
  called_at: string;
  served_at: string | null;
  /** How long the ticket waited in the queue before it was called. */
  wait_seconds: number;
  /** How many times this ticket has been re-announced, of the most an Agent may (FR-DSP-028). */
  announce_count: number;
  announce_limit: number;
  /** How many times it has been missed, of the most it may be before the next Miss closes it as a no-show (FR-QUE-050). */
  miss_count: number;
  miss_limit: number;
  outcomes: SessionOutcome[];
}

export interface CounterSession {
  id: string;
  counter: SessionCounter;
  agent_id: string;
  state: SessionState;
  opened_at: string;
  closed_at: string | null;
  services: SessionService[];
  /** The ticket in progress. */
  ticket: SessionTicket | null;
  /** The agent's "held by me" list, to be cleared before the session can close (FR-AGT-013). */
  held: SessionTicket[];
  /** The most tickets this session may hold at once. */
  hold_limit: number;
}

/** A counter the caller may occupy, with the Services it would let them serve (FR-AGT-001, FR-AGT-003). */
export interface SessionCounterOption {
  counter: SessionCounter;
  /** Another session holds it, so opening it now would be refused. */
  occupied: boolean;
  services: SessionService[];
}

export interface OpenSessionInput {
  counter_id: string;
  /** The Services to serve this session; leave it out to serve them all (FR-AGT-003). */
  service_ids?: string[];
}

export interface CompleteInput {
  /** From the ticket's `outcomes`; required whenever that list is not empty. */
  outcome_code_id?: string;
  note?: string;
}
