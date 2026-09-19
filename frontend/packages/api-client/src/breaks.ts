import type { LocalisedText } from "./catalogue";
import type { SessionBreak, SessionCounter } from "./sessions";

/** Break types, agent availability and the break report (SRS §11.3; FR-AGT-020..022, FR-AGT-024). */

/**
 * A kind of break an Agent may take, such as lunch or prayer (FR-AGT-020). `max_minutes` is the longest it may run, or null for
 * no limit. Break types are organisation-wide and are deactivated, never deleted, so a break already taken keeps its type.
 */
export interface BreakType {
  id: string;
  name_i18n: LocalisedText;
  max_minutes: number | null;
  active: boolean;
  created_at: string;
  updated_at: string;
}

/** A break type is replaced as a whole: a `max_minutes` left out means no limit. */
export interface BreakTypeInput {
  name_i18n: LocalisedText;
  max_minutes?: number | null;
}

/** `available` in an open session, `on_break`, `closing`, or `offline` with no live session (FR-AGT-024). */
export type AgentStatus = "available" | "on_break" | "closing" | "offline";

export interface AgentAvailability {
  agent_id: string;
  agent_name: string | null;
  status: AgentStatus;
  session_id: string;
  counter: SessionCounter;
  /** The break the agent is on, while `status` is `on_break`. */
  break: SessionBreak | null;
}

/**
 * What an admin sets an agent's availability to (FR-AGT-024): `on_break`, which needs a `break_type_id`, or `available`, which
 * ends the break. The `reason` is kept in the audit entry.
 */
export interface AvailabilityInput {
  status: "available" | "on_break";
  break_type_id?: string;
  reason?: string;
}

export interface BreakReportRow {
  agent_id: string;
  agent_name: string | null;
  break_type: { id: string; name_i18n: LocalisedText; max_minutes: number | null };
  count: number;
  total_seconds: number;
  average_seconds: number;
  /** How many of these breaks ran longer than the type's maximum. */
  overruns: number;
}

export interface BreakReport {
  from: string | null;
  to: string | null;
  rows: BreakReportRow[];
}

/** Which breaks to report on: those started in `[from, to)` (ISO-8601 instants), for one agent and one type. */
export interface BreakReportQuery {
  from?: string;
  to?: string;
  agent_id?: string;
  break_type_id?: string;
}
