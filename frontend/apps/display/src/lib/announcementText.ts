import { createI18n, SHIPPED_PACKS, spokenNumber } from "@qms/i18n";

/**
 * Everything a call announcement's template needs (ticket 29, FR-DSP-021): the token number and its prefix's spoken
 * form per language (FR-DSP-030), the counter label, the calling Service's name per language and the zone's floor.
 * {@code visitorName} is optional and only ever spoken when the calling Service enables it (FR-DSP-022): this build
 * never receives a visitor's name over the zone's live feed (FR-SEC-020's public-display default), so it stays
 * {@code null} in practice even when a Service turns the flag on -- see the ticket 29 traceability notes.
 */
export interface AnnouncementData {
  tokenNumber: string;
  tokenPrefix: string | null;
  tokenPrefixSpoken: Record<string, string>;
  counterLabel: string;
  serviceNames: Record<string, string>;
  floorLabel: string;
  visitorName?: string | null;
}

/** The digits of a token number after its known prefix, whatever separator (if any) the site's numbering rule uses. */
function tokenDigits(tokenNumber: string, tokenPrefix: string | null): string {
  if (!tokenPrefix || !tokenNumber.startsWith(tokenPrefix)) return tokenNumber;
  return tokenNumber.slice(tokenPrefix.length).replace(/^[^0-9]+/, "");
}

function localised(names: Record<string, string>, language: string): string {
  return names[language] ?? names.en ?? Object.values(names)[0] ?? "";
}

/** The spoken token: its prefix's configured spoken form (falling back to the raw prefix if none is recorded yet) followed by its digits spoken in `language`. */
export function spokenToken(data: Pick<AnnouncementData, "tokenNumber" | "tokenPrefix" | "tokenPrefixSpoken">, language: string): string {
  const prefix = data.tokenPrefix ? (data.tokenPrefixSpoken[language] ?? data.tokenPrefixSpoken.en ?? data.tokenPrefix) : "";
  const digits = spokenNumber(tokenDigits(data.tokenNumber, data.tokenPrefix), language);
  return [prefix, digits].filter(Boolean).join(" ");
}

/**
 * The announcement text for one language (FR-DSP-021), built from the shipped language pack's template (a form of
 * per-language configuration this codebase already uses everywhere else, e.g. `nowServing.*`). The visitor-name
 * variant is used only when `announceVisitorName` is on for the calling Service (FR-DSP-022) and a name is present.
 */
export function announcementText(data: AnnouncementData, language: string, announceVisitorName: boolean): string {
  const i18n = createI18n({ packs: SHIPPED_PACKS, language, systemDefault: "en" });
  const params = {
    token: spokenToken(data, language),
    counter: data.counterLabel,
    service: localised(data.serviceNames, language),
    floor: data.floorLabel,
  };
  return announceVisitorName && data.visitorName
    ? i18n.t("announcement.templateWithVisitor", { ...params, visitorName: data.visitorName })
    : i18n.t("announcement.template", params);
}
