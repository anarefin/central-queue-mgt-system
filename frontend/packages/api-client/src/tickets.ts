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
}

/** A rounded range in minutes, never an exact promise. The API sends null until estimation exists. */
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
  /** Null once the ticket has left the queue. */
  position: number | null;
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
}

export interface QueuedTicket {
  id: string;
  token_number: string;
  state: TicketState;
  position: number;
  origin_channel: Channel;
  queued_at: string;
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
