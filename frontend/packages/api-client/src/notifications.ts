/** The notification pipeline's admin surface (ticket 38, SRS §14, FR-NTF-*): the trigger catalogue and its per-Site/Service
 * settings, templates with their preview, and the delivery log. */

export type NotificationMessageStatus = "queued" | "sent" | "failed" | "suppressed";

export interface NotificationTriggerCatalogueEntry {
  trigger_key: string;
  default_channel_order: string[];
  /** Bypasses quiet hours and can never be opted out of (FR-NTF-031, FR-NTF-035). */
  essential: boolean;
  /** The fixed set of {{variable}} names a template for this trigger may use (FR-NTF-021). */
  variables: string[];
}

/** A trigger's enabled state and channel order, resolved for a Site or a Service under it (FR-NTF-010). */
export interface NotificationTriggerSetting {
  trigger_key: string;
  enabled: boolean;
  channel_order: string[];
  site_overridden: boolean;
  service_overridden: boolean;
}

export interface NotificationTriggerSettingInput {
  enabled: boolean;
  channel_order?: string[] | null;
}

export interface NotificationTemplate {
  id: string;
  trigger_key: string;
  channel: string;
  language: string;
  subject: string | null;
  body: string;
  updated_at: string;
  updated_by: string | null;
}

export interface NotificationTemplateInput {
  subject?: string | null;
  body: string;
}

export interface NotificationTemplatePreview {
  subject: string | null;
  body: string;
}

export interface NotificationDeliveryAttempt {
  id: string;
  message_id: string;
  attempt_no: number;
  channel: string;
  status: "sent" | "failed";
  provider_response: string | null;
  attempted_at: string;
}

export interface NotificationMessage {
  id: string;
  trigger_key: string;
  channel: string;
  channel_order: string[];
  channel_index: number;
  language: string;
  urgent: boolean;
  site_id: string | null;
  service_id: string | null;
  ticket_id: string | null;
  visitor_id: string | null;
  variables: Record<string, string>;
  rendered_subject: string | null;
  rendered_body: string | null;
  status: NotificationMessageStatus;
  attempt_count: number;
  created_at: string;
  sent_at: string | null;
}

export interface NotificationMessageWithAttempts {
  message: NotificationMessage;
  attempts: NotificationDeliveryAttempt[];
}

export interface NotificationMessageQuery {
  ticketId?: string;
  visitorId?: string;
  status?: NotificationMessageStatus;
  limit?: number;
}
