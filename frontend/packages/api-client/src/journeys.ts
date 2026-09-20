import type { LocalisedText } from "./catalogue";
import type { Ticket } from "./tickets";

/**
 * Journeys and multi-stop Visits (ticket 31, ADR-0007, FR-ISS-022). A Journey is an ordered or unordered set of Service
 * stops, from a template or ad hoc, all sharing one Visit; a stop's own `ticket` is present once it is issued, and
 * absent (`state: "planned"`) while an ordered Journey has not reached it yet (FR-QUE-061).
 */
export interface IssueJourneyInput {
  /** Issues the template's own stops, in its own order; `service_ids`/`ordered` are ignored when this is given (FR-QUE-060). */
  journey_template_id?: string;
  /** An ad hoc Journey's stops, in the order to issue them; at least two, required together with `ordered`. */
  service_ids?: string[];
  /** Required for an ad hoc Journey: whether the next stop issues only once the one before completes (FR-QUE-061, FR-QUE-062). */
  ordered?: boolean;
  /** Chosen once for the whole Journey; an ordered Journey's later stops inherit it (FR-QUE-061). */
  priority_class_id?: string;
  visitor_id?: string;
  purpose_note?: string;
}

export interface JourneyStopResult {
  seq: number;
  service_id: string;
  service_names: LocalisedText;
  /** The stop's ticket's state, or `planned` before it is issued. */
  state: string;
  ticket?: Ticket;
  /** For an unordered Journey, the one stop shown as callable soonest (FR-QUE-062); always false for an ordered one. */
  soonest: boolean;
}

export interface JourneyResult {
  visit_id: string;
  ordered: boolean;
  stops: JourneyStopResult[];
}

/** A Journey template offered at a Site, for Reception's picker (FR-QUE-060). */
export interface JourneyTemplateSummary {
  id: string;
  name_i18n: LocalisedText;
  ordered: boolean;
  stops: Array<{ seq: number; service_id: string; service_names: LocalisedText }>;
}

export interface JourneySettings {
  enabled: boolean;
}
