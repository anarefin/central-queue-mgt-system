/** Organisation branding and the printed-token template (ticket 27, SRS §7.5, FR-CFG-030..032). */

/** The fixed set of fields a printed token layout may show (FR-CFG-031); nothing outside this list is ever offered. */
export type PrintField =
  | "token_number"
  | "building"
  | "floor"
  | "service_group"
  | "service"
  | "visitor_code"
  | "visitor_name"
  | "visitor_category"
  | "counter"
  | "issue_time"
  | "estimated_wait"
  | "qr_code"
  | "notice_line";

/** Every fixed field, in the order the editor and preview show them. */
export const PRINT_FIELDS: PrintField[] = [
  "token_number",
  "building",
  "floor",
  "service_group",
  "service",
  "visitor_code",
  "visitor_name",
  "visitor_category",
  "counter",
  "issue_time",
  "estimated_wait",
  "qr_code",
  "notice_line",
];

export interface OrgBranding {
  org_name: string;
  primary_color: string;
  logo_url: string | null;
  updated_at: string | null;
  updated_by: string | null;
}

export interface BrandingInput {
  org_name: string;
  primary_color: string;
  logo_url?: string | null;
}

export interface PrintTemplate {
  fields: PrintField[];
  notice_line: string | null;
  updated_at: string | null;
  updated_by: string | null;
}

export interface PrintTemplateInput {
  fields: PrintField[];
  notice_line?: string | null;
}
