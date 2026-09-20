"use client";

import { createVisitorAuth, loadRuntimeConfig, type ApiClient, type VisitorAuthSession } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";

export const BASE_PATH = process.env.NEXT_PUBLIC_BASE_PATH ?? "";

interface RuntimeState {
  client: ApiClient | null;
  apiOrigin: string | null;
  /** A registered visitor's own signed-in session (ticket 41, FR-MOB-001). The anonymous ticket-status page (ticket
   * 37) never reads this at all: every request it makes carries the ticket's own secret instead, passed per call
   * (`client.tickets.visitorView`/`visitorCancel`, always `{ anonymous: true }`), so the one shared `client` here
   * serves both a signed-in visitor's own calls and every anonymous ticket-secret call without either interfering
   * with the other. */
  visitorSession: VisitorAuthSession | null;
  /** Set when config.json could not be loaded; the page cannot talk to the API without it. */
  error: Error | null;
}

const RuntimeContext = createContext<RuntimeState>({ client: null, apiOrigin: null, visitorSession: null, error: null });

/** Loads `config.json` at boot (ADR-0012) and exposes an `ApiClient` and `VisitorAuthSession` bound to the
 * configured API origin. The session holds the access token in memory only (API-017). */
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
    if (apiOrigin === null) return { client: null, apiOrigin: null, visitorSession: null, error };
    const { client, session } = createVisitorAuth({ apiOrigin, getLanguage: () => languageRef.current });
    return { client, apiOrigin, visitorSession: session, error };
  }, [apiOrigin, error]);

  return <RuntimeContext.Provider value={value}>{children}</RuntimeContext.Provider>;
}

export function useApi(): RuntimeState {
  return useContext(RuntimeContext);
}
