"use client";

import type { VisitorAuthStatus, VisitorMe } from "@qms/api-client";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, useSyncExternalStore, type ReactNode } from "react";
import { useApi } from "./runtime";

interface AccountState {
  status: VisitorAuthStatus;
  me: VisitorMe | null;
  requestOtp: (email: string) => Promise<void>;
  verifyOtp: (email: string, code: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AccountContext = createContext<AccountState | null>(null);

/**
 * A registered visitor's own sign-in state (ticket 41, FR-MOB-001). On load it tries to resume the session from the
 * HttpOnly visitor refresh cookie (silent refresh); the access token itself lives only in the {@code
 * VisitorAuthSession}'s memory (API-017). Entirely separate from the anonymous ticket-status page (ticket 37), which
 * never touches this at all.
 */
export function AccountProvider({ children }: { children: ReactNode }) {
  const { visitorSession, client } = useApi();
  const [me, setMe] = useState<VisitorMe | null>(null);

  const status = useSyncExternalStore<VisitorAuthStatus>(
    (onChange) => (visitorSession ? visitorSession.subscribe(onChange) : () => undefined),
    () => visitorSession?.status ?? "unknown",
    () => "unknown",
  );

  useEffect(() => {
    void visitorSession?.restore();
  }, [visitorSession]);

  useEffect(() => {
    if (status !== "authenticated" || !client) {
      setMe(null);
      return;
    }
    let cancelled = false;
    client.visitorAuth.me().then(
      (result) => !cancelled && setMe(result),
      () => undefined,
    );
    return () => {
      cancelled = true;
    };
  }, [status, client]);

  const requestOtp = useCallback(
    async (email: string) => {
      if (!visitorSession) throw new Error("The app is not configured yet");
      await visitorSession.requestOtp(email);
    },
    [visitorSession],
  );
  const verifyOtp = useCallback(
    async (email: string, code: string) => {
      if (!visitorSession) throw new Error("The app is not configured yet");
      await visitorSession.verifyOtp(email, code);
    },
    [visitorSession],
  );
  const logout = useCallback(async () => {
    await visitorSession?.logout();
  }, [visitorSession]);

  const value = useMemo(() => ({ status, me, requestOtp, verifyOtp, logout }), [status, me, requestOtp, verifyOtp, logout]);
  return <AccountContext.Provider value={value}>{children}</AccountContext.Provider>;
}

export function useAccount(): AccountState {
  const value = useContext(AccountContext);
  if (!value) throw new Error("useAccount must be used inside <AccountProvider>");
  return value;
}
