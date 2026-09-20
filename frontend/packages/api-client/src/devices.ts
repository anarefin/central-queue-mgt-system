import type { PrintField } from "./branding";

/** Device pairing and fleet management (SRS §20.2, §20.4, §21.2; FR-OPS-011, FR-OPS-041, FR-OPS-042; ticket 24). */
export type DeviceKind = "kiosk" | "display";
export type DeviceConnectivity = "online" | "stale" | "offline";
export type DeviceCommand = "reload" | "config_changed";

/**
 * Body of a pairing or refresh exchange. Unlike staff login, the refresh token travels here too, in the body, not a
 * cookie: a kiosk/display shell is not a browser and keeps its own credential (API-017).
 */
export interface DeviceTokenResponse {
  device_id: string;
  kind: DeviceKind;
  site_id: string;
  zone_id: string | null;
  access_token: string;
  token_type: "Bearer";
  /** Seconds until the access token expires (at most 900, API-013). */
  expires_in: number;
  refresh_token: string;
}

/** A device on the central health view (FR-OPS-041). `connectivity` is computed server-side so every client agrees. */
/** A display's board fields are meaningless and just this build's defaults for a kiosk (ticket 28). */
export interface DeviceView {
  id: string;
  kind: DeviceKind;
  site_id: string;
  zone_id: string | null;
  label: string;
  active: boolean;
  paired_at: string;
  last_heartbeat_at: string | null;
  last_app_version: string | null;
  connectivity: DeviceConnectivity;
  layout: DisplayLayout;
  language_cycle: string[];
  next_n: number;
  highlight_seconds: number;
  columns: DisplayColumn[];
  assignment_scope: DisplayAssignmentScope;
  assignment_ids: string[];
}

export interface CreatePairingCodeInput {
  kind: DeviceKind;
  site_id: string;
  /** Required for a display, must be null/omitted for a kiosk. */
  zone_id?: string | null;
  label: string;
}

/** The raw code is shown once; only its hash is ever stored server-side. */
export interface PairingCodeResponse {
  code: string;
  expires_at: string;
}

/** Body of `GET /config/bootstrap`: everything a device needs to render without a second round trip. */
export interface DeviceBootstrap {
  branding: {
    site_name: string;
    default_language: string;
    /**
     * The organisation-wide branding of ticket 27 (FR-CFG-030): logo, primary colour and organisation name, as
     * opposed to `site_name` above. Optional only so older fixtures that predate ticket 27 keep type-checking; a
     * real backend always sends all three.
     */
    org_name?: string;
    primary_color?: string;
    logo_url?: string | null;
  };
  languages: string[];
  /** Present only for a display, scoped to one zone; a kiosk's layout is site-level only, so this is null. */
  layout: {
    zone: {
      id: string;
      name: string;
      building_label: string | null;
      floor_label: string;
      counters: { id: string; label: string }[];
    };
  } | null;
  service_tree: KioskServiceTreeGroup[];
  /** The printed token layout (ticket 27, FR-CFG-031); optional for the same fixture-compatibility reason as above. */
  print_template?: { fields: PrintField[]; notice_line: string | null };
}

/** A service the kiosk selection tree can lead to (ticket 26). `visitor_identifier` decides the identify step: `not_required`, `optional` or `mandatory` (FR-CFG-013). */
export interface KioskServiceTreeEntry {
  id: string;
  name_i18n: Record<string, string>;
  visitor_identifier: "not_required" | "optional" | "mandatory";
}

/** One option of a group's custom kiosk-selection level (ticket 26, FR-ISS-010). */
export interface KioskCustomLevelOption {
  id: string;
  name_i18n: Record<string, string>;
}

/** A group's custom level, or absent when the group has none. */
export interface KioskCustomLevel {
  name_i18n: Record<string, string>;
  options: KioskCustomLevelOption[];
}

/**
 * A group's own kiosk selection tree (ticket 26, FR-ISS-010, FR-ISS-011): the group and service levels always apply.
 * `individual_selectable` only ever offers the on-duty-agent level when `team_selectable` is also true (a group has
 * exactly one team, so the team level itself is never its own screen — see the backend `ServiceGroup`'s header).
 * `custom_level` is null when the group has none.
 */
export interface KioskServiceTreeGroup {
  id: string;
  name_i18n: Record<string, string>;
  services: KioskServiceTreeEntry[];
  team_selectable: boolean;
  individual_selectable: boolean;
  custom_level: KioskCustomLevel | null;
}

// ---- display board (ticket 28, SRS §12, FR-DSP-001..005, FR-DSP-007, FR-DSP-012) -----------------------------------

/** The only shipped layout this build implements; ticket 30 adds `split_media`, `single_counter` and `summary_board`. */
export type DisplayLayout = "now_serving_table";
export type DisplayColumn = "token" | "counter" | "service" | "staff";
export type DisplayAssignmentScope = "zone" | "counters" | "queues";

export interface DisplayAssignment {
  scope: DisplayAssignmentScope;
  ids: string[];
}

/** Body of `PUT /devices/{id}/display-config` (staff, `config:org_sites_zones`); a field left out keeps its default. */
export interface DisplayConfigInput {
  layout?: DisplayLayout;
  language_cycle?: string[];
  next_n?: number;
  highlight_seconds?: number;
  columns?: DisplayColumn[];
  assignment?: DisplayAssignment;
}

export interface DisplayConfig {
  id: string;
  layout: DisplayLayout;
  language_cycle: string[];
  next_n: number;
  highlight_seconds: number;
  columns: DisplayColumn[];
  assignment: DisplayAssignment;
}

/** The chimes this build ships (ticket 29, FR-DSP-025); an admin picks one, never a free-text sound file. */
export type ZoneChime = "chime_standard" | "chime_soft" | "chime_alert";

/**
 * A zone's voice-announcement settings (ticket 29, SRS §12.3-12.4): the chime and its volume (FR-DSP-025), an
 * optional daily quiet period during which audio is suppressed but the display still updates (FR-DSP-027, "HH:mm"
 * or null for neither end set), the languages announcements play in and their order (FR-DSP-023), and the most
 * announcements ever queued at once, keeping only the most recent per Counter beyond that (FR-DSP-026).
 */
export interface DisplayZoneRef {
  id: string;
  name: string;
  building_label: string | null;
  floor_label: string;
  chime: ZoneChime;
  chime_volume: number;
  quiet_start: string | null;
  quiet_end: string | null;
  announcement_languages: string[];
  max_announce_queue_depth: number;
}

/**
 * One Counter of the zone: null token/state/service/staff when nothing is being called or served there right now.
 * {@code token_prefix_spoken} (FR-DSP-030) is the calling Service's token prefix spoken per language, empty until an
 * admin records one (FR-I18N-041); {@code announce_visitor_name} is the calling Service's flag (FR-DSP-022).
 */
export interface DisplayServingEntry {
  counter_id: string;
  counter_label: string;
  token_number: string | null;
  state: "called" | "serving" | null;
  service_id: string | null;
  service_names: Record<string, string>;
  staff_name: string | null;
  token_prefix: string | null;
  token_prefix_spoken: Record<string, string>;
  announce_visitor_name: boolean;
}

export interface DisplayNextToken {
  token_number: string;
  position: number;
}

/** The next-token strip of one queue in the zone (FR-DSP-005: next N tokens per queue). */
export interface DisplayNextGroup {
  service_id: string;
  service_names: Record<string, string>;
  tokens: DisplayNextToken[];
}

/**
 * Body of `GET /devices/{id}/display-state` (device-authenticated, FR-DSP-012): everything a display needs to
 * resume its assigned zone and layout with no login. `serving` and `next` are always the whole zone's live state;
 * a display assigned to fewer Counters or queues than the whole zone (`assignment.scope` other than `zone`) filters
 * this down to its own assignment itself -- the same filter it applies to every `zone:` topic update afterwards.
 */
export interface DisplayState {
  zone: DisplayZoneRef;
  layout: DisplayLayout;
  language_cycle: string[];
  columns: DisplayColumn[];
  next_n: number;
  highlight_seconds: number;
  assignment: DisplayAssignment;
  serving: DisplayServingEntry[];
  next: DisplayNextGroup[];
}
