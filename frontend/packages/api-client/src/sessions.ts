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
  /** The visitor came in with an appointment (FR-AGT-030). */
  is_appointment: boolean;
  /**
   * The visitor, as far as the ticket has one and the caller's role may see it (FR-AGT-030, FR-AGT-034): a field outside the role's
   * configured set, or one the ticket does not have, is absent, and so is `visitor` itself when none is left.
   */
  visitor?: { code?: string; name?: string; category?: string };
  /** What the visit is for, in the visitor's or reception's words; absent when there is none or the role may not see it. */
  purpose_note?: string;
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
  /** A called ticket that has waited for its Agent longer than the call timeout: the Agent may now return it to the queue (FR-QUE-032). */
  call_timed_out: boolean;
  outcomes: SessionOutcome[];
}

/** The break a session is on while its state is `on_break` (FR-AGT-021): its type and when it started, so the console can show its clock. */
export interface SessionBreak {
  id: string;
  type: { id: string; name_i18n: LocalisedText; max_minutes: number | null };
  started_at: string;
}

export interface CounterSession {
  id: string;
  counter: SessionCounter;
  agent_id: string;
  state: SessionState;
  opened_at: string;
  closed_at: string | null;
  services: SessionService[];
  /** The first ticket in progress; `tickets` has all of them. */
  ticket: SessionTicket | null;
  /** Every ticket called or serving: more than one only when the Services allow parallel serving (FR-AGT-010, FR-AGT-011). */
  tickets: SessionTicket[];
  /** The agent's "held by me" list, to be cleared before the session can close (FR-AGT-013). */
  held: SessionTicket[];
  /** The most tickets this session may hold at once. */
  hold_limit: number;
  /** The break the session is on, or null. */
  break: SessionBreak | null;
  /** Whether a call would be taken now: the session is open and the counter has room for another ticket (FR-AGT-010). */
  can_call: boolean;
  /** How long a called ticket waits for its Agent before they are prompted (FR-QUE-032); 0 means never. */
  call_timeout_seconds: number;
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

/**
 * A transfer (F7, FR-QUE-052). The `note` is mandatory. The target is a Service, optionally narrowed to one of its counters or
 * agents, not both; leave `service_id` out for a counter or agent of the ticket's own Service.
 */
export interface TransferInput {
  service_id?: string;
  counter_id?: string;
  agent_id?: string;
  note: string;
}

/**
 * What a transfer did (ADR-0006): the ticket in service closed as `transferred`, and a successor with the same token number and
 * visit now waits in the target queue, ahead of later arrivals by `head_start_minutes` of waiting (FR-QUE-053). `session` is the
 * session as it stands, so the console can redraw itself.
 */
export interface TransferResult {
  predecessor: { id: string; token_number: string; state: "transferred" };
  successor: {
    id: string;
    token_number: string;
    state: "waiting";
    service: { id: string; name_i18n: LocalisedText };
    visit_id: string;
    predecessor_ticket_id: string;
    counter_id?: string;
    agent_id?: string;
    head_start_minutes: number;
    position: number | null;
  };
  session: CounterSession;
}

/** Where the ticket in service may go: the active Services of the session's site, and the counters and agents that can take them. */
export interface TransferTargets {
  services: Array<{ id: string; name_i18n: LocalisedText }>;
  counters: Array<{ id: string; label: string; zone_name: string; service_ids: string[] }>;
  agents: Array<{ id: string; name: string; service_ids: string[] }>;
}

/**
 * The caller's own current day (FR-AGT-040): tickets completed, tickets waiting for the Services of their session, the average service time
 * (null until one has been served) and the break time so far. It is only ever the caller's own figures; there is nothing to rank Agents by.
 */
export interface AgentDay {
  served: number;
  in_queue: number;
  average_service_seconds: number | null;
  break_seconds: number;
  as_of: string;
}
