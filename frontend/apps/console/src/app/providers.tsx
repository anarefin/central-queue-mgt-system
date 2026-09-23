"use client";

import { I18nProvider } from "@qms/i18n/react";
import { ThemeProvider } from "@qms/ui";
import { useState, type ReactNode } from "react";
import { AuthProvider } from "../lib/auth";
import { AppLabelsProvider } from "../lib/labels";
import { RuntimeProvider } from "../lib/runtime";
import { SessionStatusProvider } from "../lib/session-status";
import { UserLanguageContext } from "../lib/user-language";

export function Providers({ children }: { children: ReactNode }) {
  const [userLanguage, setUserLanguage] = useState<string | null>(null);
  return (
    <ThemeProvider>
      <UserLanguageContext.Provider value={setUserLanguage}>
        <I18nProvider userLanguage={userLanguage}>
          <RuntimeProvider>
            <AuthProvider>
              <AppLabelsProvider>
                <SessionStatusProvider>{children}</SessionStatusProvider>
              </AppLabelsProvider>
            </AuthProvider>
          </RuntimeProvider>
        </I18nProvider>
      </UserLanguageContext.Provider>
    </ThemeProvider>
  );
}
