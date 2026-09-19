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
