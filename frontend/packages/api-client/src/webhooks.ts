/** Outbound webhooks (ticket 57, SRS §22.3, FR-INT-020..022): an admin subscribes an endpoint to any §21.4 event
 * type with a secret; delivery is HMAC-SHA256-signed, retried with backoff, and every delivery is logged and
 * replayable. A failing endpoint never affects queue operation. */

/** The closed §21.4 event type set, exactly as `integration.webhook.WebhookEventType` transcribes it server-side. */
export const WEBHOOK_EVENT_TYPES = [
  "ticket.issued",
  "ticket.called",
  "ticket.reannounced",
  "ticket.missed",
  "ticket.serving",
  "ticket.held",
  "ticket.completed",
  "ticket.no_show",
  "ticket.cancelled",
  "ticket.transferred",
  "ticket.position_changed",
  "queue.estimate_changed",
  "session.opened",
  "session.break_started",
  "session.break_ended",
  "session.closed",
  "alert.raised",
  "alert.acknowledged",
  "device.command",
  "config.changed",
] as const;

export type WebhookEventType = (typeof WEBHOOK_EVENT_TYPES)[number];

export type WebhookDeliveryStatus = "queued" | "sent" | "failed";

/** {@code secret} is present only in the response to creating the endpoint or rotating its secret; it is never
 * readable back after that. */
export interface WebhookEndpoint {
  id: string;
  description: string;
  url: string;
  event_types: WebhookEventType[];
  active: boolean;
  created_at: string;
  updated_at: string;
  secret?: string;
}

/** {@code secret} is accepted only on create (a blank or absent one is generated server-side); update never
 * touches it. */
export interface WebhookEndpointInput {
  description: string;
  url: string;
  event_types: WebhookEventType[];
  secret?: string;
}

export interface WebhookDelivery {
  id: string;
  event_id: string;
  endpoint_id: string;
  event_type: string;
  occurred_at: string;
  data: Record<string, unknown>;
  status: WebhookDeliveryStatus;
  attempt_count: number;
  last_error: string | null;
  created_at: string;
  delivered_at: string | null;
}

export interface WebhookDeliveryAttempt {
  id: string;
  delivery_id: string;
  attempt_no: number;
  success: boolean;
  response_status: number | null;
  error: string | null;
  attempted_at: string;
}

export interface WebhookDeliveryWithAttempts {
  delivery: WebhookDelivery;
  attempts: WebhookDeliveryAttempt[];
}

export interface WebhookDeliveryQuery {
  endpointId?: string;
  eventType?: string;
  status?: WebhookDeliveryStatus;
  limit?: number;
}
