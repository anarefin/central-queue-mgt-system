"use client";

import { AuthenticatedLabelsProvider } from "@qms/i18n/react";
import type { ReactNode } from "react";
import { useAuth } from "./auth";
import { useApi } from "./runtime";

/**
 * Terminology remapping for the admin app (SRS §3.2, ticket 69) — see `AuthenticatedLabelsProvider` for the shared
 * "wait for sign-in before fetching" plumbing; this just wires it to the admin app's own auth/runtime hooks.
 */
export function AppLabelsProvider({ children }: { children: ReactNode }) {
  const { client } = useApi();
  const { status } = useAuth();
  return (
    <AuthenticatedLabelsProvider client={client} authenticated={status === "authenticated"}>
      {children}
    </AuthenticatedLabelsProvider>
  );
}
