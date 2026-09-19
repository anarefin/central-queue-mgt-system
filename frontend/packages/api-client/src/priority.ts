import type { LocalisedText } from "./catalogue";
import type { NameRef, TicketState } from "./tickets";

/** Priority classes, ordering strategies and the queue dry-run (SRS §10.2, §10.3; FR-QUE-010, FR-QUE-021, FR-QUE-023). */
export type QueueStrategy = "weighted_wait" | "strict_priority" | "fifo";
export const QUEUE_STRATEGIES: readonly QueueStrategy[] = ["weighted_wait", "strict_priority", "fifo"];

/**
 * A class of visitor that queues ahead of others. `headstart_minutes` is virtual waiting granted on arrival (normal is
 * 0); `max_wait_minutes` is when its tickets are escalated, or null for never. The default class is the normal one that
 * every ticket without a class belongs to: its Head start stays 0 and it cannot be deactivated.
 */
export interface PriorityClass {
  id: string;
  name_i18n: LocalisedText;
  headstart_minutes: number;
  max_wait_minutes: number | null;
  /** Replaces the service's prefix when a numbering rule takes its prefix from the priority class. */
  token_prefix_override: string | null;
  is_default: boolean;
  active: boolean;
  created_at: string;
  updated_at: string;
}

/** A class is replaced as a whole: a field left out means none (or 0 for the Head start). */
export interface PriorityClassInput {
  name_i18n: LocalisedText;
  headstart_minutes?: number;
  max_wait_minutes?: number | null;
  token_prefix_override?: string | null;
}

export interface RoutingStrategy {
  service_group_id: string;
  strategy: QueueStrategy;
  /** True while the group has not chosen one and so uses `weighted_wait`. */
  is_default: boolean;
  available: QueueStrategy[];
}

/** The terms of one score, in minutes. The score is their sum. */
export interface ScoreTerms {
  effective_wait_minutes: number;
  headstart_minutes: number;
  appointment_bonus: number;
  escalation_bonus: number;
  score_adjustment_minutes: number;
  /** True when escalation set a negative adjustment aside (FR-QUE-022). */
  adjustment_overridden: boolean;
}

export interface DryRunTicket {
  id: string;
  token_number: string;
  state: TicketState;
  position: number;
  priority_class: NameRef | null;
  max_wait_minutes: number | null;
  queued_at: string;
  terms: ScoreTerms;
  score: number;
  /** Past its class's maximum wait, so it goes before every ticket that is not. */
  escalated: boolean;
}

export interface QueueDryRun {
  service: NameRef;
  site_id: string;
  /** The strategy that produced `position`. */
  strategy: QueueStrategy;
  computed_at: string;
  waiting_count: number;
  tickets: DryRunTicket[];
}
