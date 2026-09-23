"use client";

import { createAuth, loadRuntimeConfig, type ApiClient, type AuthSession } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { createRealtimeClient, streamUrl, type RealtimeClient } from "@qms/realtime-client";
import { applyBrand } from "@qms/ui";
import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";

export const BASE_PATH = process.env.NEXT_PUBLIC_BASE_PATH ?? "";

interface RuntimeState {
  client: ApiClient | null;
  session: AuthSession | null;
  /** The live channel to the hub, which falls back to polling by itself where WebSocket is blocked (SRS §21, FR-QUE-084). */
  realtime: RealtimeClient | null;
  /** Set when config.json could not be loaded; the app cannot talk to the API without it. */
  error: Error | null;
}

const RuntimeContext = createContext<RuntimeState>({ client: null, session: null, realtime: null, error: null });

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
    if (apiOrigin === null) return { client: null, session: null, realtime: null, error };
    const { client, session } = createAuth({ apiOrigin, getLanguage: () => languageRef.current });
    const realtime = createRealtimeClient({
      url: streamUrl(apiOrigin),
      getAccessToken: () => session.accessToken,
      // The socket re-authenticates before its token expires and after the hub drops it for a changed user (ADR-0009).
      refreshAccessToken: async () => {
        await session.refresh();
        return session.accessToken;
      },
      fetchSnapshot: (topic) => client.stream.snapshot(topic),
    });
    return { client, session, realtime, error };
  }, [apiOrigin, error]);

  useEffect(() => () => value.realtime?.close(), [value.realtime]);

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
