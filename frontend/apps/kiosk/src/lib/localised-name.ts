/** A name in the reader's language, falling back to the site default, never to a raw key or nothing (FR-I18N-011). */
export function localisedName(names: Record<string, string>, language: string, defaultLanguage: string): string {
  return names[language] ?? names[defaultLanguage] ?? Object.values(names)[0] ?? "";
}
