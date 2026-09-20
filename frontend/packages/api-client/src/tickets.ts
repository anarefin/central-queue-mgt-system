import type { Channel, LocalisedText } from "./catalogue";

/** Ticket, queue and site-service resources (SRS §20.4, §20.5). */
export type TicketState =
  | "remote"
  | "waiting"
  | "paused"
  | "called"
  | "serving"
  | "held"
  | "completed"
  | "transferred"
  | "no_show"
  | "cancelled"
  | "forfeited";

export interface NameRef {
  id: string;
  name_i18n: LocalisedText;
}

/** Where the visitor waits. The API leaves `zone` null when no counter serves the service yet. */
export interface TicketZone {
  id: string;
  name: string;
  building_label: string | null;
  floor_label: string;
  /** An optional static image an admin set for the zone (FR-MOB-032, ticket 37); null means none. */
  wayfinding_image_url: string | null;
}

/** A rounded range in minutes, never an exact promise ("about 15–20 minutes"); show it as a range, never as one figure. */
export interface EstimatedWait {
  low: number;
  high: number;
}

export interface Ticket {
  id: string;
  /** Always Western Arabic digits (FR-I18N-020); show it as is. */
  token_number: string;
  state: TicketState;
  service: NameRef;
  service_group: NameRef;
  site_id: string;
  zone: TicketZone | null;
  visit_id: string;
  origin_channel: Channel;
  /** The Priority class the ticket queues under; the API names the default (normal) class for a ticket without one. */
  priority_class: NameRef | null;
  /** Null once the ticket has left the queue. */
  position: number | null;
  /** Null once the ticket has left the queue. */
  estimated_wait_minutes: EstimatedWait | null;
  issued_at: string;
  queued_at: string;
  version: number;
  /** Present only in the response to the request that issued the ticket; it cannot be read back. */
  secret?: string;
}

export interface IssueTicketInput {
  service_id: string;
  /** Defaults to `reception` for staff. */
  origin_channel?: Channel;
  /** When the device says the request happened (ISO 8601); defaults to the server's time. */
  occurred_at?: string;
  /** The Priority class staff assign (FR-QUE-011); leave it out for the default (normal) class. */
  priority_class_id?: string;
  /** The visitor this ticket is for (FR-ISS-020), from a directory search or a fresh walk-in registration. */
  visitor_id?: string;
  /** A free-text note visible only to the agent who serves this ticket (FR-ISS-020). */
  purpose_note?: string;
}

/** What a staff action on a ticket leaves behind (change of class, cancel). `position` is null once the ticket has left the queue. */
export interface TicketChange {
  id: string;
  token_number: string;
  state: TicketState;
  /** The class the ticket now queues under; the default (normal) class is named too. */
  priority_class_id: string;
  position: number | null;
  /** The ticket's version after the change, for the next `If-Match`. */
  version: number;
}

/** A change of a waiting ticket's class (FR-QUE-012): the reason is mandatory and goes to the audit log. */
export interface ReprioritiseInput {
  priority_class_id: string;
  reason: string;
}

export interface QueuedTicket {
  id: string;
  token_number: string;
  state: TicketState;
  position: number;
  origin_channel: Channel;
  queued_at: string;
  priority_class: NameRef | null;
  /** Past its class's maximum wait (FR-QUE-022). */
  escalated: boolean;
}

export interface QueueSnapshot {
  service: NameRef;
  site_id: string;
  waiting_count: number;
  estimated_wait_minutes: EstimatedWait | null;
  tickets: QueuedTicket[];
}

export interface SiteServiceItem {
  id: string;
  name_i18n: LocalisedText;
  service_group: NameRef;
  token_prefix: string;
  icon: string | null;
  display_order: number;
  waiting_count: number;
  estimated_wait_minutes: EstimatedWait | null;
}

export interface SiteServices {
  site_id: string;
  default_language: string;
  items: SiteServiceItem[];
}

/** A fresh key for one issuing action; keep it until the action succeeds or is refused, so a retry cannot issue twice. */
export function newIdempotencyKey(): string {
  return globalThis.crypto.randomUUID();
}

/** The header an anonymous visitor's mobile web page sends instead of a bearer token (§20.2, FR-SEC-033, ticket 37). */
export const TICKET_CREDENTIAL_HEADER = "X-Ticket-Secret";

/** Where the visitor waits, plus its optional wayfinding image (FR-MOB-032, ticket 37). */
export interface VisitorTicketZone {
  id: string;
  name: string;
  building_label: string | null;
  floor_label: string;
  wayfinding_image_url: string | null;
}

/**
 * What the visitor ticket page shows (§20.2, §21.2, ticket 37): live position, estimate range, the token currently being
 * served for the same Service (FR-MOB-013), and where to go (FR-MOB-032). The same shape arrives from
 * `GET /tickets/{id}/visitor`, `POST /tickets/{id}/visitor-cancel` and the {@code ticket:} realtime topic, so one type
 * covers all three.
 */
export interface VisitorTicketView {
  ticket_id: string;
  token_number: string;
  state: TicketState;
  service_id: string;
  /** Null once the ticket has left the queue. */
  position: number | null;
  /** Null once the ticket has left the queue. */
  estimated_wait_minutes: EstimatedWait | null;
  /** Null when nothing of this Service is being served right now. */
  now_serving_token_number: string | null;
  zone: VisitorTicketZone | null;
  /** When this view was computed (server time, ISO 8601): show it as "as of" whenever the page is not live (FR-MOB-040). */
  updated_at: string;
}

/**
 * The body of `POST /kiosk/tickets` (ticket 25, extended by ticket 26): the visitor's chosen Service, and the rest of
 * the selection tree they walked. `visitor_id` is who `GET /kiosk/visitors/identify` resolved them to, or omitted for
 * no identification (FR-ISS-013). `agent_id` is the on-duty Agent picked at the individual level, or omitted for
 * anyone on the Service's team (FR-ISS-012). `custom_level_id` is the option picked at the group's custom level, or
 * omitted when it has none or the visitor skipped it (FR-ISS-010).
 */
export interface KioskIssueTicketInput {
  service_id: string;
  visitor_id?: string;
  agent_id?: string;
  custom_level_id?: string;
}

/** `GET /kiosk/visitors/identify` (ticket 26, FR-ISS-013, FR-ISS-014): only name and category, nothing else stored about the visitor. */
export interface KioskVisitorIdentity {
  visitor_id: string;
  name: string;
  category: string | null;
}

/** One on-duty Agent of a group's team the kiosk may offer at the individual level (ticket 26, FR-ISS-012). */
export interface KioskAgentOption {
  agent_id: string;
  name: string;
  queue_length: number;
  /** Warn the visitor: this Agent's own queue already has more people waiting for them than the group's shared queue. */
  queue_longer_than_group: boolean;
}
