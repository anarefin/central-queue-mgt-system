/** Site, zone and counter resources (FR-CFG-001..004). Timestamps are UTC ISO-8601; render them in `Site.timezone`. */
export interface Site {
  id: string;
  name: string;
  code: string;
  /** IANA zone id, for example `Asia/Dhaka`. */
  timezone: string;
  address: string;
  default_language: string;
  /** In display order (FR-I18N-002). */
  enabled_languages: string[];
  active: boolean;
  created_at: string;
  updated_at: string;
}

export interface SiteInput {
  name: string;
  code: string;
  timezone: string;
  address: string;
  default_language: string;
  enabled_languages: string[];
}

export interface Zone {
  id: string;
  site_id: string;
  name: string;
  building_label: string | null;
  floor_label: string;
  display_order: number;
  active: boolean;
  created_at: string;
  updated_at: string;
}

/** On update, an empty `building_label` clears it. */
export interface ZoneInput {
  name: string;
  floor_label: string;
  building_label?: string;
  display_order?: number;
}

export interface Counter {
  id: string;
  zone_id: string;
  site_id: string;
  label: string;
  location_note: string | null;
  active: boolean;
  created_at: string;
  updated_at: string;
}

/** On update, an empty `location_note` clears it. */
export interface CounterInput {
  label: string;
  location_note?: string;
}

export interface Items<T> {
  items: T[];
}
