"use client";

import { createAuth, loadRuntimeConfig, type ApiClient, type AuthSession } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { applyBrand } from "@qms/ui";
import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";

export const BASE_PATH = process.env.NEXT_PUBLIC_BASE_PATH ?? "";

interface RuntimeState {
  client: ApiClient | null;
  session: AuthSession | null;
  /** Set when config.json could not be loaded; the app cannot talk to the API without it. */
  error: Error | null;
}

const RuntimeContext = createContext<RuntimeState>({ client: null, session: null, error: null });

/**
 * Loads `config.json` at boot (ADR-0012) and exposes an ApiClient and AuthSession bound to the configured API origin.
 * The session holds the access token in memory only (API-017).
 */
export function RuntimeProvider({ children, configUrl = `${BASE_PATH}/config.json` }: { children: ReactNode; configUrl?: string }) {
  const { language } = useI18n();
  const languageRef = useRef(language);
  languageRef.current = language;

  const [apiOrigin, setApiOrigin] = useState<string | null>(null);
  const [error, setError] = useState<Error | null>(null);

  useEffect(() => {
    let cancelled = false;
    loadRuntimeConfig(fetch, configUrl).then(
      (config) => !cancelled && setApiOrigin(config.apiOrigin),
      (cause: Error) => !cancelled && setError(cause),
    );
    return () => {
      cancelled = true;
    };
  }, [configUrl]);

  const value = useMemo<RuntimeState>(() => {
    if (apiOrigin === null) return { client: null, session: null, error };
    const { client, session } = createAuth({ apiOrigin, getLanguage: () => languageRef.current });
    return { client, session, error };
  }, [apiOrigin, error]);

  // The login screen itself needs the org's brand, before there is any session (ticket 62, FR-CFG-030): the public
  // theme read, not the staff-only /branding endpoint. A failure just leaves the design system's own default accent.
  useEffect(() => {
    if (!value.client) return;
    value.client.branding.theme().then(applyBrand, () => undefined);
  }, [value.client]);

  return <RuntimeContext.Provider value={value}>{children}</RuntimeContext.Provider>;
}

export function useApi(): RuntimeState {
  return useContext(RuntimeContext);
}
