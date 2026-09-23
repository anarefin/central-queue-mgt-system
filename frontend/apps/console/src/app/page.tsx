"use client";

import { useI18n } from "@qms/i18n/react";
import { CounterConsole } from "../components/CounterConsole";
import { RequireAuth } from "../components/RequireAuth";

/**
 * The serving desk (SRS §11): a slim heading — the counter, session state, break timer, user menu and theme
 * toggle now live in the app shell's own top bar (ticket 64) — then straight into {@link CounterConsole}. No
 * sidebar here: the desk is a single, focused, keyboard-first screen.
 */
export default function Home() {
  const { t } = useI18n();

  return (
    <RequireAuth>
      <div className="mx-auto flex w-full max-w-6xl flex-col gap-4">
        <h1 className="text-2xl font-semibold text-fg">{t("console.title")}</h1>
        <CounterConsole />
      </div>
    </RequireAuth>
  );
}
