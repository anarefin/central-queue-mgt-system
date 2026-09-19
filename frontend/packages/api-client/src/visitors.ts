/** The visitor directory and walk-in registration (SRS §8.3, §22.2, FR-ISS-020, FR-ISS-021). */

/** A directory hit (FR-INT-012): `id` is what a caller passes as `visitor_id` to issue a ticket for this visitor. */
export interface VisitorMatch {
  id: string;
  external_code: string | null;
  name: string | null;
  category: string | null;
  phone: string | null;
  flags: Record<string, string>;
}

/** The body of `POST /visitors` (FR-ISS-021). `name` and `phone` are the minimum record; the rest is optional and may be dropped by server configuration (FR-SEC-023). */
export interface RegisterVisitorInput {
  name: string;
  phone: string;
  email?: string;
  category?: string;
  purpose?: string;
}

/** What registering a walk-in returns: the minimum record plus the pass reference to hand the visitor. */
export interface VisitorRegistration {
  id: string;
  pass_reference: string;
  name: string;
  phone: string;
  email: string | null;
  category: string | null;
  purpose: string | null;
}

/**
 * FR-INT-011's column mapping: which CSV header name feeds each target field. `external_code_column` and
 * `name_column` are mandatory; the rest may be `null`, meaning that CSV has no such column. The same mapping serves
 * a manual upload and every scheduled folder pickup.
 */
export interface VisitorImportMapping {
  external_code_column: string;
  name_column: string;
  phone_column: string | null;
  email_column: string | null;
  category_column: string | null;
}

/** The body of `POST /visitors/import`: a manual upload's raw CSV text and its file name. */
export interface VisitorImportUploadInput {
  filename: string;
  content: string;
}

/** One CSV row FR-INT-011's validation report rejected. `line` is 1-based and counts the header row. */
export interface VisitorImportError {
  line: number;
  field: string;
  code: string;
}

/** FR-INT-011's validation report for one CSV file, manual or scheduled. */
export interface VisitorImportReport {
  id: string;
  source: "manual" | "scheduled";
  filename: string | null;
  started_at: string;
  completed_at: string;
  total_rows: number;
  inserted_count: number;
  updated_count: number;
  failed_count: number;
  status: "completed" | "failed";
  errors: VisitorImportError[];
}
