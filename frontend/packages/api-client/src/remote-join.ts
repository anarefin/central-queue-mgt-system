/** A registered visitor's own remote join of a Service's queue, before arriving (SRS §13.2, ticket 42, FR-MOB-010..012). */

/**
 * A Service's remote-join policy (FR-MOB-011), shown before the visitor joins (FR-MOB-023): `max_distance_m` null
 * means the distance check is off. When `virtual_queue_enabled` is false, joining is refused outright.
 */
export interface RemoteJoinPolicy {
  service_id: string;
  virtual_queue_enabled: boolean;
  max_distance_m: number | null;
  max_remote_share_pct: number;
  join_window_minutes: number;
  arrival_deadline_minutes: number;
}

/** The visitor's own device position at the moment of joining; omit both when the policy sets no distance cap. */
export interface RemoteJoinInput {
  latitude?: number;
  longitude?: number;
}
