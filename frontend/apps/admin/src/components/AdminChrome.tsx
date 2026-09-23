"use client";

import { useI18n } from "@qms/i18n/react";
import { AppShell, Button, ThemeToggle, type AppShellNavItem } from "@qms/ui";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import type { ReactNode } from "react";
import { useAuth } from "../lib/auth";
import { visibleAdminNavItems } from "./AdminNav";

/** Routes that stay a full-page, centred card outside the app shell (ticket 63): signed-out visitors have no
 *  sidebar, site switcher or user menu to show yet. */
const BARE_ROUTES = ["/login/", "/signed-out/"];

/**
 * Wraps every signed-in admin route in the shared `AppShell` (ticket 63): a grouped, role-gated sidebar, and a top
 * bar with the site switcher, user menu and theme toggle. `/login/` and `/signed-out/` render their own centred
 * card instead (unauthenticated, nothing to gate). This is presentation only; the API is what actually enforces
 * who may reach a route (FR-CFG-103).
 */
export function AdminChrome({ children }: { children: ReactNode }) {
  const { t } = useI18n();
  const pathname = usePathname();
  const { status, user, logout } = useAuth();
  const router = useRouter();

  if (BARE_ROUTES.includes(pathname)) return <>{children}</>;

  const nav: AppShellNavItem[] = visibleAdminNavItems(user?.roles ?? []).map((item) => ({
    href: item.href,
    label: t(item.labelKey),
    active: pathname === item.href,
    group: t(item.group),
  }));

  async function signOut() {
    await logout();
    router.replace("/signed-out/");
  }

  return (
    <AppShell
      skipToContentLabel={t("common.skipToContent")}
      menuButtonLabel={t("common.menu")}
      nav={nav}
      title={<Link href="/">{t("app.admin")}</Link>}
      siteSwitcher={<SiteSwitcher siteIds={user?.sites ?? []} />}
      themeToggle={<ThemeToggle groupLabel={t("theme.toggleLabel")} labels={{ system: t("theme.system"), light: t("theme.light"), dark: t("theme.dark") }} />}
      userMenu={
        status === "authenticated" && user ? (
          <div className="flex items-center gap-3">
            <span className="text-sm text-fg-muted">{t("auth.signedInAs", { name: user.display_name ?? user.username })}</span>
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

/** A lightweight display of the sites the signed-in user belongs to (SRS §27's "site switcher"); with just one site
 *  there is nothing to switch, so it shows as plain text. Every screen below still scopes itself the same way it
 *  did before this ticket (its own site picker where one applies) — this is chrome, not a new data flow. */
function SiteSwitcher({ siteIds }: { siteIds: string[] }) {
  const { t } = useI18n();
  if (siteIds.length === 0) return null;
  if (siteIds.length === 1) return <span className="text-sm text-fg-muted">{siteIds[0]}</span>;
  return (
    <label className="flex items-center gap-2 text-sm text-fg-muted">
      <span className="sr-only">{t("admin.chrome.siteSwitcher")}</span>
      <select
        aria-label={t("admin.chrome.siteSwitcher")}
        defaultValue={siteIds[0]}
        className="rounded-md border border-border bg-surface px-2 py-1 text-sm text-fg"
      >
        {siteIds.map((id) => (
          <option key={id} value={id}>
            {id}
          </option>
        ))}
      </select>
    </label>
  );
}
