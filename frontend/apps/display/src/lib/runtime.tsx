"use client";

import { createDeviceAuth, loadRuntimeConfig, type ApiClient, type DeviceSession } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { createRealtimeClient, streamUrl, type RealtimeClient } from "@qms/realtime-client";
import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";

export const BASE_PATH = process.env.NEXT_PUBLIC_BASE_PATH ?? "";

interface RuntimeState {
  client: ApiClient | null;
  session: DeviceSession | null;
  /** The live channel to the hub, for the `device:{id}` topic a paired device watches (FR-OPS-042). */
  realtime: RealtimeClient | null;
  /** Set when config.json could not be loaded; the app cannot talk to the API without it. */
  error: Error | null;
}

const RuntimeContext = createContext<RuntimeState>({ client: null, session: null, realtime: null, error: null });

/**
 * Loads `config.json` at boot (ADR-0012) and exposes an ApiClient and DeviceSession bound to the configured API
 * origin. The device's access token is held in memory only; its refresh credential is the device's own to persist
 * (API-017).
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
    const { client, session } = createDeviceAuth({ apiOrigin, getLanguage: () => languageRef.current });
    const realtime = createRealtimeClient({
      url: streamUrl(apiOrigin),
      getAccessToken: () => session.accessToken,
      refreshAccessToken: async () => {
        await session.refresh();
        return session.accessToken;
      },
      fetchSnapshot: (topic) => client.stream.snapshot(topic),
    });
    return { client, session, realtime, error };
  }, [apiOrigin, error]);

  useEffect(() => () => value.realtime?.close(), [value.realtime]);

  return <RuntimeContext.Provider value={value}>{children}</RuntimeContext.Provider>;
}

export function useApi(): RuntimeState {
  return useContext(RuntimeContext);
}
