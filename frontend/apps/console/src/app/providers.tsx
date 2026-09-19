"use client";

import { I18nProvider } from "@qms/i18n/react";
import { useState, type ReactNode } from "react";
import { AuthProvider } from "../lib/auth";
import { RuntimeProvider } from "../lib/runtime";
import { UserLanguageContext } from "../lib/user-language";

export function Providers({ children }: { children: ReactNode }) {
  const [userLanguage, setUserLanguage] = useState<string | null>(null);
  return (
    <UserLanguageContext.Provider value={setUserLanguage}>
      <I18nProvider userLanguage={userLanguage}>
        <RuntimeProvider>
          <AuthProvider>{children}</AuthProvider>
        </RuntimeProvider>
      </I18nProvider>
    </UserLanguageContext.Provider>
  );
}
