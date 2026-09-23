"use client";

import { createContext, useContext, useState, type ReactNode } from "react";

export interface SessionStatus {
  counterLabel: string;
  state: "open" | "on_break" | "closing" | "closed" | "force_closed";
  /** Set only while `state` is `on_break` (FR-AGT-021). */
  breakStartedAt: string | null;
  breakMaxMinutes: number | null;
}

interface SessionStatusState {
  status: SessionStatus | null;
  setStatus: (status: SessionStatus | null) => void;
}

const SessionStatusContext = createContext<SessionStatusState>({ status: null, setStatus: () => undefined });

/**
 * Lets the counter console publish its own counter, session state and break (SRS §11) up to the app shell's slim
 * top bar (ticket 64), the same shape `UserLanguageContext` already uses for the auth layer to reach the i18n
 * layer: the child that owns the state calls the setter this exposes, and the shell reads the value with
 * {@link useSessionStatus}. Never throws without a provider (the console's tests render pages directly, without
 * the app shell) — a missing provider just means nothing to show.
 */
export function SessionStatusProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<SessionStatus | null>(null);
  return <SessionStatusContext.Provider value={{ status, setStatus }}>{children}</SessionStatusContext.Provider>;
}

export function useSessionStatus(): SessionStatus | null {
  return useContext(SessionStatusContext).status;
}

export function useSetSessionStatus(): (status: SessionStatus | null) => void {
  return useContext(SessionStatusContext).setStatus;
}
