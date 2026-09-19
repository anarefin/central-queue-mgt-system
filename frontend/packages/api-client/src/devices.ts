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
  branding: { site_name: string; default_language: string };
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
  service_tree: {
    id: string;
    name_i18n: Record<string, string>;
    services: { id: string; name_i18n: Record<string, string> }[];
  }[];
}
