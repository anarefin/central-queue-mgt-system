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
