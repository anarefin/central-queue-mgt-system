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

/** Report export (ticket 49, FR-RPT-003): `POST /reports/{key}/export`. */
export type ReportExportFormat = "csv" | "xlsx" | "pdf";

/** The same nine filters `run` takes (FR-RPT-001), plus the format to export in; there is no paging or sort — an
 * export is always every row the filter reaches. */
export interface ReportExportInput {
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
  format: ReportExportFormat;
}

export type ReportExportJobStatus = "queued" | "running" | "done" | "failed";

/** `GET /reports/jobs/{id}` (FR-RPT-004): a background export's progress and, once `done`, when its download link expires. */
export interface ReportExportJob {
  id: string;
  report_key: string;
  format: string;
  status: ReportExportJobStatus;
  row_count: number | null;
  requested_at: string;
  completed_at: string | null;
  expires_at: string | null;
  error: string | null;
}

/** `POST /reports/{key}/export`'s answer: the file itself when it was generated inline, or the job id to poll
 * (`reports.job`) when it crossed the async row threshold (FR-RPT-004). */
export type ReportExportOutcome = { kind: "ready"; blob: Blob; filename: string } | { kind: "queued"; jobId: string };

/** The six operational report keys ticket 50 adds to the catalogue (SRS §16.1), grouped from `reporting.ticket_fact`
 * (and, for `counter`, the live counter-session/break tables) to one row per grain value over one requested period.
 * `break` (ticket 16) is reachable under the same `POST /reports/{key}/run` endpoint but keeps its own request/
 * response shape (`BreakReportQuery`/`BreakReport`, `./breaks`), unchanged. */
export type OperationalReportKey = "visitor-flow" | "counter" | "agent" | "service" | "department" | "site";

export const OPERATIONAL_REPORT_KEYS: OperationalReportKey[] = ["visitor-flow", "counter", "agent", "service", "department", "site"];

export type ReportGrain = "hour" | "day" | "month" | "year";

/** FR-RPT-001's filters plus a bounded range (mandatory here, unlike `detailed-token`: a period comparison needs a
 * period to mirror) and, for `visitor-flow` only, the bucket grain. */
export interface OperationalReportRequest {
  from: string;
  to: string;
  site_id?: string;
  zone_id?: string;
  service_group_id?: string;
  service_id?: string;
  agent_id?: string;
  priority_class_id?: string;
  channel?: Channel;
  visitor_category?: string;
  grain?: ReportGrain;
}

/** One report row's or one totals block's metrics: numbers, a plain string name/label, a per-language name
 * (`service`/`department`), or `null`. The exact keys depend on which report key produced it. */
export type OperationalReportValue = string | number | boolean | LocalisedText | null;

export type OperationalReportRow = Record<string, OperationalReportValue>;

/** One metric's absolute and percentage difference against the previous equivalent period (FR-RPT-010,
 * FR-MON-011); `percent` is `null` when the previous value was zero (undefined, not infinite). */
export interface OperationalReportChange {
  absolute: number;
  percent: number | null;
}

/** `POST /reports/{key}/run`'s answer for every operational report key (ticket 50). */
export interface OperationalReportResponse {
  key: OperationalReportKey;
  generated_at: string;
  from: string;
  to: string;
  previous_from: string;
  previous_to: string;
  rows: OperationalReportRow[];
  totals: OperationalReportRow;
  previous_totals: OperationalReportRow;
  change: Record<string, OperationalReportChange>;
  /** Report-specific secondary breakdown that does not fit the row grain (today, only the service report's
   * "average and P90 wait per hour band" array under `wait_by_hour_band`, §15.3). */
  extra: Record<string, unknown> | null;
}
