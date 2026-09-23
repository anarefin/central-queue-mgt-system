/** The first-run setup wizard and vertical profiles (SRS §3, §26.2, ticket 56). */

export interface StarterService {
  name_i18n: Record<string, string>;
  token_prefix: string;
}

export interface StarterPriorityClass {
  name_i18n: Record<string, string>;
  headstart_minutes: number;
}

export interface NumberingDefaults {
  sequence_start: number;
  padding: number;
  reset_boundary: string;
}

/** What a vertical profile carries (§3.3): label overrides, a starter catalogue, priority classes, numbering
 * defaults, report/KPI defaults and feature flags. */
export interface VerticalProfile {
  id: string;
  /** Label key (e.g. `entity.visitor`) to language to value (§3.2). */
  labels: Record<string, Record<string, string>>;
  starter_services: StarterService[];
  priority_classes: StarterPriorityClass[];
  numbering_defaults: NumberingDefaults;
  report_defaults: string[];
  kpi_thresholds: Record<string, number>;
  feature_flags: Record<string, boolean>;
}

export interface ActiveProfile {
  id: string;
  applied_at: string | null;
  applied_by: string | null;
}

export interface TestTokenState {
  issued: boolean;
  printed: boolean;
  called: boolean;
  announced: boolean;
  ticket_id: string | null;
  token_number: string | null;
}

export interface SetupState {
  profile_applied: boolean;
  active_profile: ActiveProfile | null;
  org_and_sites: boolean;
  zones_and_counters: boolean;
  services_and_numbering: boolean;
  users_and_roles: boolean;
  devices_registered: boolean;
  test_token: TestTokenState;
  go_live_ready: boolean;
  go_live_at: string | null;
}

/** One service group or service {@link SeedCatalogueResult} created or left alone (ticket 67). */
export interface SeedCatalogueItem {
  kind: "service_group" | "service";
  name: string;
}

/** As {@link SeedCatalogueItem}, plus why it was skipped: `already_exists` (matched by English name) or
 * `prefix_in_use` (another active service at the Site already holds that token prefix). */
export interface SeedCatalogueSkippedItem extends SeedCatalogueItem {
  reason: string;
}

/** The result of seeding one Site's starter catalogue and numbering from the active vertical profile (ticket 67):
 * what was created, and what was left alone and why. Running it twice creates nothing new. */
export interface SeedCatalogueResult {
  service_group_id: string;
  created: SeedCatalogueItem[];
  skipped: SeedCatalogueSkippedItem[];
}
