import type { LanguageMeta, Pack } from "./packs";

interface IndexEntry extends LanguageMeta {
  code: string;
}

/**
 * Loads language packs an installation added after the release (FR-I18N-001). The proxy serves
 * `<base>/index.json` listing `{code, locale, numerals}` and `<base>/<code>.json` with the messages. Anything
 * missing or malformed is skipped: a broken extra pack must never take the app down.
 */
export async function loadExtraPacks(fetchImpl: typeof fetch = fetch, base = "/i18n"): Promise<Record<string, Pack>> {
  const packs: Record<string, Pack> = {};
  try {
    const indexResponse = await fetchImpl(`${base}/index.json`, { cache: "no-store" });
    if (!indexResponse.ok) return packs;
    const index = (await indexResponse.json()) as { languages?: IndexEntry[] };
    for (const entry of index.languages ?? []) {
      const response = await fetchImpl(`${base}/${entry.code}.json`, { cache: "no-store" });
      if (!response.ok) continue;
      const messages = (await response.json()) as Record<string, string>;
      packs[entry.code] = { messages, meta: { locale: entry.locale, numerals: entry.numerals } };
    }
  } catch {
    // ignore: fall back to the shipped packs
  }
  return packs;
}
