/** Appointment availability and staff booking (SRS §9.1, §9.2, FR-APT-010..016). */

/** One open slot on a date, with what capacity is left right now (FR-APT-010, FR-APT-011). */
export interface AvailabilitySlot {
  start: string;
  end: string;
  remaining_capacity: number;
}

/** `GET /services/{id}/appointments/availability?date=`: only slots with capacity left. */
export interface Availability {
  service_id: string;
  date: string;
  slots: AvailabilitySlot[];
}

export type AppointmentSource = "phone" | "walk_in" | "staff" | "visitor";

export type AppointmentState = "held_slot" | "booked" | "rescheduled" | "checked_in" | "cancelled" | "no_show" | "converted";

/**
 * The body of `POST /appointments` (FR-APT-013, FR-APT-015): either an existing `visitor_id` from the directory, or a
 * minimal `contact_name`/`contact_phone` (and optional `contact_email`) for a visitor not yet known — the same two
 * ways Reception already captures a visitor for a walk-in ticket. `source`, `visitor_id` and the contact fields are
 * all omitted by a registered visitor's own self-service booking (ticket 41): the server fills in the caller's own
 * visitor id and the `visitor` source itself from their JWT, never trusting a client-supplied value for either.
 */
export interface BookAppointmentInput {
  service_id: string;
  date: string;
  start: string;
  end: string;
  source?: AppointmentSource;
  visitor_id?: string;
  contact_name?: string;
  contact_phone?: string;
  contact_email?: string;
  preferred_agent_id?: string;
  purpose_note?: string;
  language?: string;
  priority_class_id?: string;
}

/** The body of `PATCH /appointments/{id}` (FR-APT-020, FR-APT-021): a new slot, keeping the same reference code. A
 * visitor may act only up to the configured cut-off before the *current* slot; a staff caller may act any closer,
 * but only with a `reason`. */
export interface RescheduleAppointmentInput {
  date: string;
  start: string;
  end: string;
  reason?: string;
}

/** The booked appointment (FR-APT-014): `reference_code` is what the visitor is given, and what the QR shown to them encodes. */
export interface Appointment {
  id: string;
  reference_code: string;
  service_id: string;
  date: string;
  start: string;
  end: string;
  state: AppointmentState;
  source: AppointmentSource;
  visitor_id: string;
  preferred_agent_id: string | null;
  purpose_note: string | null;
  language: string | null;
}
