import type { LocalisedText } from "./catalogue";

/** The live dashboard (SRS §15.1, ticket 46): every FR-MON-003 tile plus FR-QUE-033's served-per-counter view, under
 * one filter (FR-MON-002) that round-trips into the response so the screen's own URL can carry it. */

/** The filter a live dashboard view is under; `site_id` is the only mandatory part. */
export interface DashboardFilter {
  site_id: string;
  zone_id?: string;
  service_group_id?: string;
  service_id?: string;
  priority_class_id?: string;
}

export interface DashboardWaitingGroup {
  service_group_id: string;
  service_group_name: LocalisedText;
  count: number;
  longest_wait_seconds: number;
}

export interface DashboardServingTicket {
  ticket_id: string;
  token_number: string;
  counter_id: string;
  counter_label: string;
  agent_id: string | null;
  agent_name: string | null;
  elapsed_seconds: number;
}

export interface DashboardCounters {
  open: number;
  on_break: number;
  closed: number;
  idle_with_queue: number;
}

export interface DashboardLongestWait {
  ticket_id: string;
  token_number: string;
  service_id: string;
  service_name: LocalisedText;
  wait_seconds: number;
  /** Past its own Priority class's maximum wait (FR-QUE-022). */
  escalated: boolean;
  /** Past the Service's own SLA wait target. */
  sla_breached: boolean;
}

export interface DashboardThroughput {
  served: number;
  cancelled: number;
  no_show: number;
  transferred: number;
}

export interface DashboardAppointments {
  booked: number;
  checked_in: number;
  no_show: number;
  upcoming_next_hour: number;
}

export interface DashboardRemoteQueue {
  remote: number;
  approaching: number;
  present: number;
  forfeited: number;
}

export interface DashboardDeviceHealth {
  kiosks_offline: number;
  displays_offline: number;
  /** Printer devices are not modelled yet (Phase 2): an empty state, not a fabricated count. */
  printers_offline: number | null;
}

export interface DashboardCounterThroughput {
  counter_id: string;
  label: string;
  /** The live session {@code POST /sessions/{id}/force-close} needs for a supervisor's own force-close act (FR-MON-004). */
  session_id: string | null;
  served_count: number;
}

export interface DashboardSnapshot {
  site_id: string;
  zone_id: string | null;
  service_group_id: string | null;
  service_id: string | null;
  priority_class_id: string | null;
  generated_at: string;
  waiting_now: DashboardWaitingGroup[];
  serving_now: DashboardServingTicket[];
  counters: DashboardCounters;
  longest_waits: DashboardLongestWait[];
  throughput_today: DashboardThroughput;
  appointments_today: DashboardAppointments;
  remote_queue: DashboardRemoteQueue;
  device_health: DashboardDeviceHealth;
  served_per_open_counter: DashboardCounterThroughput[];
}
