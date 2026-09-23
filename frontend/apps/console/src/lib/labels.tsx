"use client";

import { LabelsProvider } from "@qms/i18n/react";
import { useMemo, type ReactNode } from "react";
import { useAuth } from "./auth";
import { useApi } from "./runtime";

/**
 * Terminology remapping for the console app (SRS §3.2, ticket 69): `GET /labels` needs any authenticated principal,
 * so this waits for sign-in before fetching — before that, every screen just shows the pack's own default noun,
 * which is also what a failed fetch falls back to.
 */
export function AppLabelsProvider({ children }: { children: ReactNode }) {
  const { client } = useApi();
  const { status } = useAuth();

  const fetchLabels = useMemo(() => {
    if (!client || status !== "authenticated") return undefined;
    return (lang: string) => client.labels.get(lang);
  }, [client, status]);

  return <LabelsProvider fetchLabels={fetchLabels}>{children}</LabelsProvider>;
}
