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

/** The five report keys ticket 51 adds to the catalogue (§16.1): `appointment`, `journey`, `feedback` and
 * `notification` share one row-per-entity shape (`DomainReportPage`, unlike the six ticket-50 keys' grouped-with-
 * comparison shape); `audit` reuses the existing `/audit` endpoint's own cursor-paged read (`AuditReportPage`),
 * so it is requested and typed separately (`runAudit`, below). */
export type DomainReportKey = "appointment" | "journey" | "feedback" | "notification";

export const DOMAIN_REPORT_KEYS: DomainReportKey[] = ["appointment", "journey", "feedback", "notification"];

/** FR-RPT-001's filters that apply to these four keys (a field a given key ignores server-side is simply left
 * out of the request the card builds), plus paging — `from`/`to` are optional here, unlike the operational keys,
 * since these are filtered lists, not a period-vs-period comparison. */
export interface DomainReportRequest {
  from?: string;
  to?: string;
  site_id?: string;
  service_group_id?: string;
  service_id?: string;
  agent_id?: string;
  visitor_category?: string;
  page?: number;
  size?: number;
}

/** One field's value in a domain report row or aggregate: a number, a plain string, a per-language name, a
 * boolean, or `null`. The exact keys depend on which report key produced it. */
export type DomainReportValue = string | number | boolean | LocalisedText | null;

export type DomainReportRow = Record<string, DomainReportValue>;

/** `POST /reports/{key}/run`'s answer for `appointment`/`journey`/`feedback`/`notification` (ticket 51). `extra`
 * carries whatever aggregate does not fit the row grain: the appointment report's no-show rate per Service, Agent
 * and visitor category plus its own adherence rate (FR-APT-043, and the §15.3 KPI ticket 50 left for this ticket),
 * or the journey report's completion rate and per-stop wait (FR-QUE-064 and its own §15.3 KPI). */
export interface DomainReportPage {
  key: DomainReportKey;
  generated_at: string;
  page: number;
  size: number;
  total_rows: number;
  total_pages: number;
  rows: DomainReportRow[];
  extra: Record<string, unknown> | null;
}

/** One row of the Audit report (§16.1's "Actor, action, entity, before and after values"), the same shape the
 * existing `GET /audit` endpoint already answers with. */
export interface AuditReportEntry {
  id: string;
  actor_id: string | null;
  actor_role: string | null;
  action: string;
  entity: string;
  entity_id: string | null;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  ip: string | null;
  device: string | null;
  reason: string | null;
  trace_id: string | null;
  occurred_at: string;
}

/** The Audit report's own cursor-paged request: `from`/`to` bound `occurred_at`; `cursor` is the previous page's
 * own `next_cursor`, `undefined` for the first page. Reuses `com.qms.audit.AuditQueryService`'s own read, so it is
 * additionally gated on `audit:read` (System/Org Admin only) on top of `reports:run_export` — a Team Admin who can
 * run every other report in this catalogue is refused this one. */
export interface AuditReportRequest {
  from?: string;
  to?: string;
  cursor?: string;
  size?: number;
}

export interface AuditReportPage {
  key: "audit";
  generated_at: string;
  items: AuditReportEntry[];
  next_cursor: string | null;
}

/** The two staffing-planning views ticket 51 adds (§16.2): cross-tabs over one requested period, not a
 * period-vs-period comparison. */
export type PlanningViewKey = "peak-hours" | "staffing-gap";

export const PLANNING_VIEW_KEYS: PlanningViewKey[] = ["peak-hours", "staffing-gap"];

/** FR-RPT-001's filters plus a mandatory bounded range (the same "a comparison/cross-tab needs a period to work
 * over" rule the operational reports' own request already carries). */
export interface PlanningViewRequest {
  from: string;
  to: string;
  site_id?: string;
  service_group_id?: string;
  service_id?: string;
  agent_id?: string;
  priority_class_id?: string;
  channel?: Channel;
  visitor_category?: string;
}

/** One hour-of-day x ISO day-of-week (1 Monday .. 7 Sunday) cell (FR-RPT-011). */
export interface PeakHoursCell {
  day_of_week: number;
  hour_of_day: number;
  ticket_count: number;
}

export interface PeakHoursResponse {
  key: "peak-hours";
  generated_at: string;
  from: string;
  to: string;
  cells: PeakHoursCell[];
}

/** One hour band's tickets offered, counter-hours available and SLA attainment (FR-RPT-012). */
export interface StaffingGapRow {
  hour_of_day: number;
  tickets_offered: number;
  counter_hours_available: number;
  sla_attainment_pct: number | null;
}

export interface StaffingGapResponse {
  key: "staffing-gap";
  generated_at: string;
  from: string;
  to: string;
  rows: StaffingGapRow[];
}
