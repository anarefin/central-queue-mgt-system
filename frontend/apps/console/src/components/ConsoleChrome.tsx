"use client";

import { useI18n } from "@qms/i18n/react";
import { APP_SHELL_BARE_ROUTES, AppShell, Badge, Button, ThemeToggle } from "@qms/ui";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import type { ReactNode } from "react";
import { useAuth } from "../lib/auth";
import { useElapsedSeconds } from "../lib/console-support";
import { useSessionStatus } from "../lib/session-status";

const WARN_STATES = new Set(["closing", "force_closed"]);

/**
 * Wraps every signed-in console route in the shared `AppShell` (ticket 64): a slim top bar with the counter, the
 * session's state, a break timer while on one, the user menu and the theme toggle — the serving desk itself has no
 * sidebar (SRS §11's whole point is a keyboard-first, single-screen desk). `/login/` and `/signed-out/` render
 * their own centred card instead. This is presentation only; the API is what actually enforces access
 * (FR-CFG-103).
 */
export function ConsoleChrome({ children }: { children: ReactNode }) {
  const { t } = useI18n();
  const pathname = usePathname();
  const { status, user, logout } = useAuth();
  const router = useRouter();
  const session = useSessionStatus();

  if (APP_SHELL_BARE_ROUTES.includes(pathname)) return <>{children}</>;

  async function signOut() {
    await logout();
    router.replace("/signed-out/");
  }

  return (
    <AppShell
      skipToContentLabel={t("common.skipToContent")}
      menuButtonLabel={t("common.menu")}
      title={
        <div className="flex flex-wrap items-center gap-3">
          <Link href="/">{t("app.console")}</Link>
          {session && <SessionStatus session={session} />}
        </div>
      }
      themeToggle={<ThemeToggle groupLabel={t("theme.toggleLabel")} labels={{ system: t("theme.system"), light: t("theme.light"), dark: t("theme.dark") }} />}
      userMenu={
        status === "authenticated" && user ? (
          <div className="flex items-center gap-3">
            <span className="text-sm text-fg-muted">{t("auth.signedInAs", { name: user.display_name ?? user.username })}</span>
            <Button variant="secondary" size="sm" type="button" onClick={() => router.push("/dashboard/")}>
              {t("dashboard.title")}
            </Button>
            <Button variant="secondary" size="sm" type="button" onClick={() => void signOut()}>
              {t("common.signOut")}
            </Button>
          </div>
        ) : undefined
      }
    >
      {children}
    </AppShell>
  );
}

function SessionStatus({ session }: { session: NonNullable<ReturnType<typeof useSessionStatus>> }) {
  const { t, formatNumber } = useI18n();
  const elapsed = useElapsedSeconds(session.state === "on_break" ? session.breakStartedAt : null);
  const variant = WARN_STATES.has(session.state) ? "warn" : session.state === "on_break" ? "info" : session.state === "open" ? "ok" : "neutral";
  return (
    <div className="flex flex-wrap items-center gap-2 text-sm text-fg-muted">
      <span>{t("console.session.counter", { label: session.counterLabel })}</span>
      <Badge variant={variant}>{t(`console.session.state.${session.state}`)}</Badge>
      {session.state === "on_break" && (
        <span role="timer" aria-live="off">
          {t("console.break.elapsed", { minutes: formatNumber(Math.floor(elapsed / 60)), seconds: formatNumber(elapsed % 60) })}
        </span>
      )}
    </div>
  );
}
