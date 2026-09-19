/** Service catalogue resources (FR-CFG-010..015, FR-AGT-032, FR-AGT-033). Names are maps of language code to text. */
export type LocalisedText = Record<string, string>;

export type Channel = "kiosk" | "reception" | "mobile" | "appointment_checkin";
export const CHANNELS: readonly Channel[] = ["kiosk", "reception", "mobile", "appointment_checkin"];

/** Whether a visitor identifier is needed before a ticket is issued (FR-CFG-013). */
export type VisitorIdentifier = "not_required" | "optional" | "mandatory";
export const VISITOR_IDENTIFIERS: readonly VisitorIdentifier[] = ["not_required", "optional", "mandatory"];

/** Which tickets a service accepts (FR-CFG-014). */
export type BookingMode = "appointment_only" | "walk_in_only" | "both";
export const BOOKING_MODES: readonly BookingMode[] = ["both", "walk_in_only", "appointment_only"];

export interface ServiceGroup {
  id: string;
  site_id: string;
  name_i18n: LocalisedText;
  /** Enabled languages of the site that have no name yet: a warning, never an error (FR-I18N-010). */
  missing_translations: string[];
  token_prefix: string;
  display_order: number;
  active: boolean;
  created_at: string;
  updated_at: string;
}

/** On update, `name_i18n` replaces the whole set of names. */
export interface ServiceGroupInput {
  name_i18n: LocalisedText;
  token_prefix: string;
  display_order?: number;
}

export interface ServiceEntry {
  id: string;
  service_group_id: string;
  site_id: string;
  name_i18n: LocalisedText;
  missing_translations: string[];
  token_prefix: string;
  expected_minutes: number;
  sla_wait_minutes: number;
  channels: Channel[];
  icon: string | null;
  display_order: number;
  visitor_identifier: VisitorIdentifier;
  booking_mode: BookingMode;
  /** A counter may have up to `parallel_limit` of this Service's tickets in progress at once (FR-AGT-010, FR-AGT-011). */
  parallel_serving: boolean;
  parallel_limit: number;
  active: boolean;
  created_at: string;
  updated_at: string;
}

/** On update, an empty `icon` clears it. */
export interface ServiceInput {
  name_i18n: LocalisedText;
  token_prefix: string;
  expected_minutes: number;
  sla_wait_minutes: number;
  channels: Channel[];
  icon?: string;
  display_order?: number;
  visitor_identifier: VisitorIdentifier;
  booking_mode: BookingMode;
  /** From 1 to 20, and at least 2 while `parallel_serving` is on; left out it is kept, or 2 for a Service just made parallel. */
  parallel_serving?: boolean;
  parallel_limit?: number;
}

/** A counter that serves a service; weight 1 is the primary counter, a higher weight a fallback (FR-CFG-011). */
export interface CounterLink {
  counter_id: string;
  service_id: string;
  preference_weight: number;
  counter_label: string;
  counter_active: boolean;
}

/** An active counter of the group's site that a service can be linked to. */
export interface CounterOption {
  id: string;
  zone_id: string;
  zone_name: string;
  label: string;
}

export interface OutcomeCode {
  id: string;
  service_id: string;
  site_id: string;
  code: string;
  label_i18n: LocalisedText;
  missing_translations: string[];
  display_order: number;
  active: boolean;
  created_at: string;
  updated_at: string;
}

/** The code is fixed once created; on update only the labels and order change. */
export interface OutcomeCodeInput {
  code: string;
  label_i18n: LocalisedText;
  display_order?: number;
}

export interface TeamMember {
  user_id: string;
  username: string;
  display_name: string | null;
  active: boolean;
  added_at: string;
}

export interface Team {
  id: string;
  service_group_id: string;
  name: string;
  members: TeamMember[];
}

/** The part of a user the team picker needs. */
export interface UserSummary {
  id: string;
  username: string;
  display_name: string | null;
  active: boolean;
}

export interface UserPage {
  items: UserSummary[];
  next_cursor: string | null;
}
