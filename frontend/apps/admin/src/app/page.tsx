"use client";

import { useI18n } from "@qms/i18n/react";
import { Card, PageHeader } from "@qms/ui";
import Link from "next/link";
import { visibleAdminNavItems } from "../components/AdminNav";
import { HealthPanel } from "../components/HealthPanel";
import { RequireAuth } from "../components/RequireAuth";
import { SetupProgressCard } from "../components/SetupProgressCard";
import { useAuth } from "../lib/auth";

/**
 * The admin overview (ticket 63): setup progress and backend health at a glance, quick links to the areas this
 * user's roles unlock (the same role gating as the sidebar, `AdminNav`), and the role names themselves. Sign-out
 * and the theme toggle live in the app shell's own top bar now, not here.
 */
export default function Home() {
  const { t } = useI18n();
  const { user } = useAuth();
  const roles = user?.roles ?? [];
  const canSeeSetup = roles.some((role) => role === "system_admin" || role === "org_admin");
  const quickLinks = visibleAdminNavItems(roles);

  return (
    <RequireAuth>
      <div className="mx-auto flex w-full max-w-5xl flex-col gap-6">
        <PageHeader
          title={t("app.admin")}
          description={user ? t("admin.overview.roles", { roles: roles.map((role) => t(`roles.${role}`)).join(", ") }) : undefined}
        />
        {canSeeSetup && <SetupProgressCard />}
        <HealthPanel />
        {quickLinks.length > 0 && (
          <Card header={t("admin.overview.quickLinks")}>
            <nav aria-label={t("admin.overview.quickLinks")} className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {quickLinks.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  className="rounded-lg border border-border p-4 text-sm font-medium text-fg motion-safe:transition-colors hover:bg-surface-muted focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
                >
                  {t(item.labelKey)}
                </Link>
              ))}
            </nav>
          </Card>
        )}
      </div>
    </RequireAuth>
  );
}
