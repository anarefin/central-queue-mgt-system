"use client";

import { ApiClient, loadRuntimeConfig } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";

export const BASE_PATH = process.env.NEXT_PUBLIC_BASE_PATH ?? "";

interface RuntimeState {
  client: ApiClient | null;
  apiOrigin: string | null;
  /** Set when config.json could not be loaded; the page cannot talk to the API without it. */
  error: Error | null;
}

const RuntimeContext = createContext<RuntimeState>({ client: null, apiOrigin: null, error: null });

/**
 * Loads `config.json` at boot (ADR-0012) and exposes a plain, always-anonymous `ApiClient` bound to the configured API
 * origin. The visitor page never holds a bearer token (§20.2): every request it makes carries the ticket's own secret
 * instead, passed per call (`client.tickets.visitorView`/`visitorCancel`), never a signed-in session.
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
    if (apiOrigin === null) return { client: null, apiOrigin: null, error };
    const client = new ApiClient({ apiOrigin, getLanguage: () => languageRef.current });
    return { client, apiOrigin, error };
  }, [apiOrigin, error]);

  return <RuntimeContext.Provider value={value}>{children}</RuntimeContext.Provider>;
}

export function useApi(): RuntimeState {
  return useContext(RuntimeContext);
}
