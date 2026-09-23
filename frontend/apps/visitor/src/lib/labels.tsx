"use client";

import { LabelsProvider } from "@qms/i18n/react";
import { useMemo, type ReactNode } from "react";
import { useApi } from "./runtime";

/**
 * Terminology remapping for the anonymous visitor pages (SRS §3.2, ticket 69): no session exists here (a visitor
 * may not even be signed in, FR-MOB-001), so this reads the seven public `entity.*` values through
 * `client.labels.public`, the same reach `branding.theme()` already has before any session.
 */
export function AppLabelsProvider({ children }: { children: ReactNode }) {
  const { client } = useApi();

  const fetchLabels = useMemo(() => {
    if (!client) return undefined;
    return (lang: string) => client.labels.public(lang);
  }, [client]);

  return <LabelsProvider fetchLabels={fetchLabels}>{children}</LabelsProvider>;
}
