"use client";

import { useI18n } from "@qms/i18n/react";
import { APP_SHELL_BARE_ROUTES, AppShell, Button, ThemeToggle, type AppShellNavItem } from "@qms/ui";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { useAuth } from "../lib/auth";
import { useApi } from "../lib/runtime";
import { visibleAdminNavItems } from "./AdminNav";

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

  if (APP_SHELL_BARE_ROUTES.includes(pathname)) return <>{children}</>;

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
      linkComponent={Link}
      title={<Link href="/">{t("app.admin")}</Link>}
      siteSwitcher={<SiteSwitcher siteIds={user?.sites ?? []} roles={user?.roles ?? []} />}
      themeToggle={<ThemeToggle groupLabel={t("theme.toggleLabel")} labels={{ system: t("theme.system"), light: t("theme.light"), dark: t("theme.dark") }} />}
      userMenu={
        status === "authenticated" && user ? (
          <div className="flex items-center gap-3">
            <span className="hidden text-sm text-fg-muted lg:inline">{t("auth.signedInAs", { name: user.display_name ?? user.username })}</span>
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
 *  did before this ticket (its own site picker where one applies) — this is chrome, not a new data flow. Sites show
 *  by name; a user who may not read the site list (a Reception operator, a Team Admin) sees nothing here rather than
 *  raw site ids. */
/** The roles the API lets read the site list (PermissionMatrix: config:org_sites_zones); anyone else is not asked. */
const SITE_READERS = ["system_admin", "org_admin"];

function SiteSwitcher({ siteIds, roles }: { siteIds: string[]; roles: string[] }) {
  const { t } = useI18n();
  const { client } = useApi();
  const [names, setNames] = useState<Record<string, string>>({});
  const key = siteIds.join(",");
  const canRead = roles.some((role) => SITE_READERS.includes(role));

  useEffect(() => {
    if (!client || !canRead || siteIds.length === 0) return;
    let cancelled = false;
    client.sites.list().then(
      (sites) => {
        if (!cancelled) setNames(Object.fromEntries(sites.items.map((site) => [site.id, site.name])));
      },
      () => undefined,
    );
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- keyed on the ids themselves, not the array's identity
  }, [client, key, canRead]);

  const named = siteIds.filter((id) => names[id]);
  if (named.length === 0) return null;
  if (named.length === 1) return <span className="text-sm text-fg-muted">{names[named[0]!]}</span>;
  return (
    <label className="flex items-center gap-2 text-sm text-fg-muted">
      <select
        aria-label={t("admin.chrome.siteSwitcher")}
        defaultValue={named[0]}
        className="rounded-md border border-border bg-surface px-2 py-1 text-sm text-fg"
      >
        {named.map((id) => (
          <option key={id} value={id}>
            {names[id]}
          </option>
        ))}
      </select>
    </label>
  );
}
