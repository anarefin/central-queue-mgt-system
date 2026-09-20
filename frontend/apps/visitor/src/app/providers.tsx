"use client";

import { I18nProvider } from "@qms/i18n/react";
import type { ReactNode } from "react";
import { RuntimeProvider } from "../lib/runtime";

export function Providers({ children }: { children: ReactNode }) {
  return (
    <I18nProvider>
      <RuntimeProvider>{children}</RuntimeProvider>
    </I18nProvider>
  );
}
