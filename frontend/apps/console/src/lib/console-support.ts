import { ApiRequestError } from "@qms/api-client";
import type { I18n } from "@qms/i18n";

/** Reasons the API gives a refused session action that the console has a sentence for. */
const REFUSALS = new Set([
  "no_ticket_waiting",
  "ticket_in_progress",
  "no_ticket_called",
  "no_ticket_serving",
  "version_mismatch",
  "session_not_open",
  "counter_occupied",
  "agent_has_open_session",
  "counter_inactive",
]);

/** A localised sentence for a failed call; the code and reason, never the server's text, choose it (SRS §20.3). */
export function describeError(t: I18n["t"], cause: unknown): string {
  if (!(cause instanceof ApiRequestError)) return t("errors.network_error");
  const reason = cause.body?.details?.reason;
  if (cause.code === "conflict" && typeof reason === "string" && REFUSALS.has(reason)) return t(`console.refused.${reason}`);
  return t(`errors.${cause.code}`);
}

/** The reason a 409 gives, when it names one. */
export function reasonOf(cause: unknown): string | undefined {
  const reason = cause instanceof ApiRequestError ? cause.body?.details?.reason : undefined;
  return typeof reason === "string" ? reason : undefined;
}

/** A name in the reader's language, falling back to English and then to whatever there is, never to nothing (FR-I18N-011). */
export function localisedName(names: Record<string, string>, language: string): string {
  return names[language] ?? names.en ?? Object.values(names)[0] ?? "";
}
