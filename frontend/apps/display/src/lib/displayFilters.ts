import type { DisplayNextGroup, DisplayServingEntry, DisplayState } from "@qms/api-client";

/**
 * A display assigned to fewer Counters than its whole zone (FR-DSP-002) narrows the zone's live feed down to its own
 * assignment; `zone`-scoped displays (this build's common case, and the only one exercised end to end by the backend
 * IT suite) show everything the zone publishes. Shared by every layout (ticket 28's now-serving table and ticket
 * 30's `split_media`), so the filter lives in exactly one place.
 */
export function filterServing(state: DisplayState): DisplayServingEntry[] {
  if (state.assignment.scope === "counters") {
    const ids = new Set(state.assignment.ids);
    return state.serving.filter((row) => ids.has(row.counter_id));
  }
  if (state.assignment.scope === "queues") {
    const ids = new Set(state.assignment.ids);
    return state.serving.filter((row) => row.service_id !== null && ids.has(row.service_id));
  }
  return state.serving;
}

/** The next-token strip's groups for this display, leaving out queues with nothing waiting (a heading with no tokens). */
export function filterNext(state: DisplayState): DisplayNextGroup[] {
  const waiting = state.next.filter((group) => group.tokens.length > 0);
  if (state.assignment.scope === "queues") {
    const ids = new Set(state.assignment.ids);
    return waiting.filter((group) => ids.has(group.service_id));
  }
  return waiting;
}

/**
 * {@code prev} with each serving row that still shows the same token as {@code fresh} replaced by fresh's copy: fills
 * in the Service and staff names a live `ticket.called` event does not carry, leaving any row whose token has moved on
 * since that read as it is.
 */
export function fillCalledRows(prev: DisplayState, fresh: DisplayState): DisplayState {
  return {
    ...prev,
    serving: prev.serving.map((row) => {
      const match = fresh.serving.find((candidate) => candidate.counter_id === row.counter_id);
      return match && match.token_number === row.token_number ? match : row;
    }),
  };
}

/** A name in one language, falling back to English, then to whatever exists, never to nothing. */
export function localised(names: Record<string, string>, language: string): string | null {
  return names[language] ?? names.en ?? Object.values(names)[0] ?? null;
}

/**
 * A name in every language of the cycle, joined for side-by-side rendering (FR-I18N-005: "or render side by side
 * where the layout allows"). Falls back the same way {@link localised} does when a language has no entry of its own.
 */
export function localisedAll(names: Record<string, string>, languages: string[]): string {
  const seen = new Set<string>();
  const parts: string[] = [];
  for (const language of languages) {
    const text = localised(names, language);
    if (text !== null && !seen.has(text)) {
      seen.add(text);
      parts.push(text);
    }
  }
  return parts.join(" / ");
}
