import type { Channel, LocalisedText } from "./catalogue";

/** The reporting store and the detailed token report (SRS §16, §18.5, ticket 48): `POST /reports/{key}/run`. Only
 * `detailed-token` exists yet; later tickets (49-51) grow the catalogue. */
export const DETAILED_TOKEN_REPORT_KEY = "detailed-token";

export type ReportSort =
  | "token_number"
  | "visitor_code"
  | "visitor_name"
  | "visitor_category"
  | "service_group"
  | "service"
  | "channel"
  | "priority_class"
  | "issued_at"
  | "called_at"
  | "served_at"
  | "closed_at"
  | "wait_seconds"
  | "service_seconds"
  | "counter"
  | "agent"
  | "outcome"
  | "transfers";

export type SortDirection = "asc" | "desc";

/** FR-RPT-001's nine filters, plus FR-RPT-002's server-side paging and sort. */
export interface DetailedTokenReportRequest {
  from?: string;
  to?: string;
  site_id?: string;
  zone_id?: string;
  service_group_id?: string;
  service_id?: string;
  agent_id?: string;
  priority_class_id?: string;
  channel?: Channel;
  visitor_category?: string;
  page?: number;
  size?: number;
  sort?: ReportSort;
  direction?: SortDirection;
}

/** One row of the detailed token report (§16.1): one row per Ticket. */
export interface DetailedTokenReportRow {
  ticket_id: string;
  token: string;
  visitor_code: string | null;
  visitor_name: string | null;
  visitor_category: string | null;
  service_group: LocalisedText;
  service: LocalisedText;
  channel: Channel;
  priority_class: LocalisedText;
  issue_time: string;
  call_time: string | null;
  start_time: string | null;
  end_time: string | null;
  wait_seconds: number | null;
  service_seconds: number | null;
  counter: string | null;
  agent: string | null;
  outcome: LocalisedText;
  transfers: number;
}

export interface DetailedTokenReportPage {
  key: string;
  generated_at: string;
  page: number;
  size: number;
  total_rows: number;
  total_pages: number;
  /** "Tickets issued" (§18.5, ADR-0006): chain heads only, never every row. */
  tickets_issued: number;
  rows: DetailedTokenReportRow[];
}
