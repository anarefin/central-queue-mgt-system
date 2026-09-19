"use client";

import { I18nProvider } from "@qms/i18n/react";
import type { ReactNode } from "react";

export function Providers({ children }: { children: ReactNode }) {
  return <I18nProvider>{children}</I18nProvider>;
}
