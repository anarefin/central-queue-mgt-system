/** Threshold alerts (SRS §15.4, §11.3 FR-AGT-023, ticket 47): per-Service thresholds (FR-MON-020), the alerts a
 * breach raises (FR-MON-021), grouping repeats (FR-MON-023) and acknowledging one with a note (FR-MON-022). */

/** A Service's own alert thresholds; a field left {@code null} leaves that metric unmonitored (FR-MON-020). */
export interface AlertThreshold {
  service_id: string;
  queue_length_max: number | null;
  longest_wait_minutes_max: number | null;
  idle_counters_with_queue_max: number | null;
  no_show_rate_percent_max: number | null;
  device_offline_minutes_max: number | null;
  /** Overrides `qms.alerts`' own default grouping window (FR-MON-023) for this Service; null keeps the default. */
  group_window_minutes: number | null;
  /** Overrides the default escalation delay (FR-MON-021) for this Service; null keeps the default, 0 turns escalation off. */
  escalation_delay_minutes: number | null;
  updated_at: string | null;
  updated_by: string | null;
}

export type AlertThresholdInput = Omit<AlertThreshold, "service_id" | "updated_at" | "updated_by">;

export type AlertThresholdType = "queue_length" | "longest_wait" | "idle_counters" | "no_show_rate" | "device_offline" | "break_overrun";

export type AlertState = "open" | "acknowledged";

/** One threshold breach streak (FR-MON-021): open until acknowledged; repeated breaches of the same key while open
 * bump `breach_count` and `last_breached_at` instead of a new alert (FR-MON-023). */
export interface Alert {
  id: string;
  site_id: string;
  /** Null for a threshold type with no Service of its own ({@code break_overrun}: a break belongs to an Agent's
   * counter session, not a Service). */
  service_id: string | null;
  threshold_type: AlertThresholdType;
  subject_id: string | null;
  state: AlertState;
  breach_count: number;
  measured_value: number;
  threshold_value: number;
  first_breached_at: string;
  last_breached_at: string;
  escalated_at: string | null;
  acknowledged_at: string | null;
  acknowledged_by: string | null;
  acknowledgement_note: string | null;
  created_at: string;
}
