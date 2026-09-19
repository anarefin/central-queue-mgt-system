"use client";

import { createContext, useContext } from "react";

/** Lets the auth layer tell the i18n layer which language the signed-in user prefers (FR-I18N-003, highest priority). */
export const UserLanguageContext = createContext<(language: string | null) => void>(() => undefined);

export function useSetUserLanguage() {
  return useContext(UserLanguageContext);
}
