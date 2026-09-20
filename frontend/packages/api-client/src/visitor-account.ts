/** A registered visitor's own sign-in and "my account" surface (SRS §13.1, §20.2; FR-MOB-001, FR-MOB-002; ticket 41). */

/** Body of OTP verify and silent refresh; the refresh token is never here, only in the HttpOnly cookie (API-017). */
export interface VisitorTokenResponse {
  access_token: string;
  token_type: "Bearer";
  /** Seconds until the access token expires (at most 900, API-013). */
  expires_in: number;
}

export interface VisitorMe {
  id: string;
  email: string;
}

/** One of a visitor's own active tickets (FR-MOB-002). Never carries a ticket secret. */
export interface TicketSummary {
  id: string;
  token_number: string;
  state: string;
  service_id: string;
  service_names: Record<string, string>;
  site_id: string;
  site_name: string;
  issued_at: string;
}

/** One of a visitor's own appointments, past or upcoming (FR-MOB-002): the same `id` `PATCH`/`DELETE /appointments/{id}` act on. */
export interface AppointmentSummary {
  id: string;
  reference_code: string;
  service_id: string;
  service_names: Record<string, string>;
  site_id: string;
  site_name: string;
  date: string;
  start: string;
  end: string;
  state: string;
}

export interface SavedSite {
  site_id: string;
  site_name: string;
}
