/** Privacy controls (SRS §25.3-25.4, ticket 54): which optional visitor fields a surface shows/captures, a
 * visitor's own data export, and its deletion (anonymisation). */

/** The two surfaces this ticket gives an Org Admin runtime control over; §25.3's other rows are already
 * admin-configurable from earlier tickets (branding's print template, a Service's `announce_visitor_name`,
 * the console's own `qms.console.visitor-fields`, and reports' `visitor_pii:view` gate). */
export type VisitorFieldSurface = "capture" | "kiosk_confirmation";

export interface VisitorFieldConfig {
  surface: VisitorFieldSurface;
  field: string;
  visible: boolean;
  updated_at: string;
  updated_by: string | null;
}

export interface VisitorExport {
  id: string;
  external_code: string | null;
  name: string | null;
  category: string | null;
  phone: string | null;
  email: string | null;
  preferred_language: string | null;
  created_at: string;
  anonymized_at: string | null;
  tickets: VisitorExportTicket[];
  notification_consent: VisitorConsent | null;
  retention_consent: VisitorConsent | null;
}

export interface VisitorExportTicket {
  id: string;
  token_number: string;
  state: string;
  service_names: Record<string, string>;
  site_id: string;
  purpose_note: string | null;
  issued_at: string;
  closed_at: string | null;
}

export interface VisitorConsent {
  granted: boolean;
  consent_text_version: string;
  recorded_at: string;
}

export interface VisitorAnonymizeResult {
  id: string;
  anonymized_at: string;
  tickets_anonymized: number;
}
