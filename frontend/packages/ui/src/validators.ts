/**
 * Client-side field validation (ticket 66, SRS §27.5): pure functions that mirror the server's own rules so a form
 * can show an error next to the field immediately, before any round trip. The server stays authoritative — every
 * rule here is deliberately kept in lockstep with its Java counterpart (see the doc comment above each group) so
 * the two sides never disagree. Nothing here writes to the DOM or calls `fetch`; wiring a field to one of these
 * lives in the form component itself.
 *
 * Each function returns an i18n message key naming what is wrong, or `undefined` when the value is valid. The
 * caller looks the key up with `t()` to render it through `TextField`/`SelectField`'s own `error` prop.
 */

export type FieldError = string | undefined;

const CODE_PATTERN = /^[A-Za-z0-9_-]+$/;

/** `Intl.supportedValuesOf` is available in every runtime this app ships to (Node ≥ 18.5, every evergreen browser),
 * but is guarded anyway: a runtime without it falls back to asking `Intl.DateTimeFormat` whether it accepts the
 * zone, which throws for anything it does not recognise. */
let ianaZoneCache: Set<string> | null | undefined;

function ianaZones(): Set<string> | null {
  if (ianaZoneCache !== undefined) return ianaZoneCache;
  try {
    ianaZoneCache = new Set(Intl.supportedValuesOf("timeZone"));
  } catch {
    ianaZoneCache = null;
  }
  return ianaZoneCache;
}

function isValidTimezone(value: string): boolean {
  const zones = ianaZones();
  if (zones) return zones.has(value);
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value });
    return true;
  } catch {
    return false;
  }
}

// ---- generic field shapes, mirroring SiteRules.required/optional -------------------------------------------------

/** A required text field: trimmed, not blank, within `max` (`SiteRules.required`). */
export function requiredText(value: string, max: number): FieldError {
  const trimmed = value.trim();
  if (trimmed === "") return "validation.required";
  if (trimmed.length > max) return "validation.tooLong";
  return undefined;
}

/** An optional text field: blank is fine, but what is there must fit within `max` (`SiteRules.optional`). */
export function optionalText(value: string, max: number): FieldError {
  return value.trim().length > max ? "validation.tooLong" : undefined;
}

// ---- Site (backend/.../configuration/site/SiteRules.java) --------------------------------------------------------

/** `SiteRules.code`: letters, digits, underscore and hyphen, required, at most 32 characters. */
export function siteCode(value: string): FieldError {
  const trimmed = value.trim();
  if (trimmed === "") return "validation.required";
  if (trimmed.length > 32) return "validation.tooLong";
  return CODE_PATTERN.test(trimmed) ? undefined : "validation.site.code.pattern";
}

/** `SiteRules.timezone`: a required, real IANA zone id. */
export function siteTimezone(value: string): FieldError {
  const trimmed = value.trim();
  if (trimmed === "") return "validation.required";
  return isValidTimezone(trimmed) ? undefined : "validation.site.timezone.invalid";
}

/** Splits a comma/whitespace-separated language list into trimmed entries, preserving order — order is meaningful
 * (FR-I18N-002: it is display order), so this never sorts or dedupes, only trims and drops empty entries. */
export function parseLanguageList(raw: string): string[] {
  return raw
    .split(/[\s,]+/)
    .map((entry) => entry.trim())
    .filter(Boolean);
}

/** `SiteRules.languages`: at least one language, and no repeats — duplicates are flagged by position, the same
 * order the admin typed them in. */
export function siteEnabledLanguages(raw: string): FieldError {
  const entries = parseLanguageList(raw);
  if (entries.length === 0) return "validation.site.languages.empty";
  const seen = new Set<string>();
  for (const entry of entries) {
    if (seen.has(entry)) return "validation.site.languages.duplicate";
    seen.add(entry);
  }
  return undefined;
}

/** `SiteRules.languages`: the default language must be among the enabled ones. Silent while `enabled` itself is
 * invalid (empty/duplicate) — that error already names the field, and this would only pile on. */
export function siteDefaultLanguage(defaultLanguage: string, enabledLanguagesRaw: string): FieldError {
  if (siteEnabledLanguages(enabledLanguagesRaw) !== undefined) return undefined;
  const entries = parseLanguageList(enabledLanguagesRaw);
  return entries.includes(defaultLanguage) ? undefined : "validation.site.defaultLanguage.notEnabled";
}

/** `SiteRules.displayOrder`: 0 to 100000 inclusive. */
export function siteDisplayOrder(value: number): FieldError {
  return Number.isFinite(value) && value >= 0 && value <= 100_000 ? undefined : "validation.site.displayOrder.range";
}

// ---- Zone audio (SiteRules.java, ticket 29, FR-DSP-023/025/026/027) -----------------------------------------------

/** `SiteRules.chimeVolume`: 0 to 100 inclusive. */
export function zoneChimeVolume(value: number): FieldError {
  return Number.isFinite(value) && value >= 0 && value <= 100 ? undefined : "validation.zone.chimeVolume.range";
}

/** `SiteRules.maxAnnounceQueueDepth`: 1 to 20 inclusive. */
export function zoneMaxAnnounceQueueDepth(value: number): FieldError {
  return Number.isFinite(value) && value >= 1 && value <= 20 ? undefined : "validation.zone.maxAnnounceQueueDepth.range";
}

/** `SiteRules.quietPeriodComplete`: a quiet period needs both a start and an end, or neither. */
export function zoneQuietPeriod(start: string, end: string): FieldError {
  const hasStart = start.trim() !== "";
  const hasEnd = end.trim() !== "";
  return hasStart !== hasEnd ? "validation.zone.quietPeriod.incomplete" : undefined;
}

// ---- Counter (HierarchyService, via SiteRules.required) -----------------------------------------------------------

/** A counter's display label: required, at most 30 characters. */
export function counterLabel(value: string): FieldError {
  return requiredText(value, 30);
}

// ---- Notice (backend/.../configuration/notice/NoticeRules.java) ---------------------------------------------------

/** `NoticeRules.dates`: `ends_at` must be strictly after `starts_at`, checked only once both are set — an
 * incomplete pair is either still being typed or is the input's own `required`, not this rule's business. */
export function noticeDates(startsAt: string, endsAt: string): FieldError {
  if (startsAt.trim() === "" || endsAt.trim() === "") return undefined;
  const start = new Date(startsAt).getTime();
  const end = new Date(endsAt).getTime();
  if (Number.isNaN(start) || Number.isNaN(end)) return undefined;
  return end > start ? undefined : "validation.notice.endsAt.notAfterStarts";
}

/** `NoticeRules.sortOrder`: 0 to 1000 inclusive. */
export function noticeSortOrder(value: number): FieldError {
  return Number.isFinite(value) && value >= 0 && value <= 1000 ? undefined : "validation.notice.sortOrder.range";
}

// ---- Reports (SRS §16, every card with a date range) ---------------------------------------------------------------

/** `from` must be on or before `to`; checked only once both are set, the same "only when both are set" convention
 * every report card already uses for its own optional filters. */
export function reportRange(from: string, to: string): FieldError {
  if (from.trim() === "" || to.trim() === "") return undefined;
  const fromTime = new Date(from).getTime();
  const toTime = new Date(to).getTime();
  if (Number.isNaN(fromTime) || Number.isNaN(toTime)) return undefined;
  return fromTime <= toTime ? undefined : "validation.reports.range.invalid";
}
