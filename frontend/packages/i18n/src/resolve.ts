export interface ResolveInput {
  /** The signed-in user's or visitor's stored preference. */
  user?: string | null;
  /** The device setting, e.g. `navigator.languages`, in priority order. */
  device?: string | readonly string[] | null;
  /** The Site default language. */
  site?: string | null;
  /** The system default; always the last resort. */
  system: string;
  enabled: readonly string[];
}

function normalise(tag: string | null | undefined, enabled: readonly string[]): string | undefined {
  const language = tag?.trim().split(/[-_]/)[0]?.toLowerCase();
  return language && enabled.includes(language) ? language : undefined;
}

/**
 * Most specific wins: user/visitor preference → device setting → site default → system default (FR-I18N-003).
 * A candidate that is not an enabled language is skipped, never returned.
 */
export function resolveLanguage(input: ResolveInput): string {
  const device = typeof input.device === "string" ? [input.device] : (input.device ?? []);
  const candidates = [input.user, ...device, input.site, input.system];
  for (const candidate of candidates) {
    const match = normalise(candidate, input.enabled);
    if (match) return match;
  }
  return input.system;
}
