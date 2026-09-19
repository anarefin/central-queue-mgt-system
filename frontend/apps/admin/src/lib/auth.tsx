"use client";

import type { AuthStatus, Me } from "@qms/api-client";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, useSyncExternalStore, type ReactNode } from "react";
import { useApi } from "./runtime";
import { useSetUserLanguage } from "./user-language";

interface AuthState {
  status: AuthStatus;
  user: Me | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

/**
 * Staff sign-in state. On load it tries to resume the session from the HttpOnly refresh cookie (silent refresh); the
 * access token itself lives only in the AuthSession's memory.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const { session, client } = useApi();
  const setUserLanguage = useSetUserLanguage();
  const [user, setUser] = useState<Me | null>(null);

  const status = useSyncExternalStore<AuthStatus>(
    (onChange) => (session ? session.subscribe(onChange) : () => undefined),
    () => session?.status ?? "unknown",
    () => "unknown",
  );

  useEffect(() => {
    void session?.restore();
  }, [session]);

  useEffect(() => {
    if (status !== "authenticated" || !client) {
      setUser(null);
      setUserLanguage(null);
      return;
    }
    let cancelled = false;
    client.auth.me().then(
      (me) => {
        if (cancelled) return;
        setUser(me);
        setUserLanguage(me.preferred_language);
      },
      () => undefined,
    );
    return () => {
      cancelled = true;
    };
  }, [status, client, setUserLanguage]);

  const login = useCallback(
    async (username: string, password: string) => {
      if (!session) throw new Error("The app is not configured yet");
      await session.login(username, password);
    },
    [session],
  );
  const logout = useCallback(async () => {
    await session?.logout();
  }, [session]);

  const value = useMemo(() => ({ status, user, login, logout }), [status, user, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside <AuthProvider>");
  return value;
}
