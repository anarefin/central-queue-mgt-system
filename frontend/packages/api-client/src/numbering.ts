/** Token numbering rules and the preview of the next Token number (FR-CFG-018). */
export type PrefixSource = "service" | "service_group" | "priority_class" | "fixed";
export const PREFIX_SOURCES: readonly PrefixSource[] = ["service_group", "service", "priority_class", "fixed"];

export type ResetBoundary = "daily" | "weekly" | "monthly" | "never";
export const RESET_BOUNDARIES: readonly ResetBoundary[] = ["daily", "weekly", "monthly", "never"];

/** A rule belongs to one Service or one Service group. */
export type NumberingScope = "service" | "service_group";

export interface NumberingRule {
  id: string;
  site_id: string;
  scope_type: NumberingScope;
  scope_id: string;
  prefix_source: PrefixSource;
  /** Set only when the prefix source is `fixed`. */
  fixed_prefix: string | null;
  sequence_start: number;
  /** Minimum digits of the sequence, 0 to 6. */
  padding: number;
  reset_boundary: ResetBoundary;
  /** Site-local time of day, `HH:mm`. */
  reset_time: string;
  /** Any string, including empty. */
  separator: string;
  created_at: string;
  updated_at: string;
}

/** The rule is replaced as a whole; a field left out takes its default (service group, 1, 3, daily, 00:00, "-"). */
export interface NumberingRuleInput {
  prefix_source?: PrefixSource;
  fixed_prefix?: string;
  sequence_start?: number;
  padding?: number;
  reset_boundary?: ResetBoundary;
  reset_time?: string;
  separator?: string;
}

/**
 * The result of setting or removing a rule. Nothing already issued is renumbered (FR-CFG-041): the count says how many
 * tickets are waiting under the scope and keep the numbers they have. `rule` is null once removed.
 */
export interface NumberingRuleChange {
  rule: NumberingRule | null;
  affected_waiting_tickets: number;
}

export interface NumberingPreviewItem {
  service_id: string;
  /** What the next ticket would be called; always Western Arabic digits (FR-I18N-020). */
  token_number: string;
  prefix: string;
  sequence: number;
  reset_key: string;
  /** When the sequence next starts over; null for a rule that never resets. */
  next_reset_at: string | null;
  /** Which rule produced it: the Service's own, its group's, or the built-in default. */
  rule_source: NumberingScope | "default";
}

export interface NumberingPreview {
  items: NumberingPreviewItem[];
}
