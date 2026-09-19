"use client";

import { useI18n } from "@qms/i18n/react";
import { Button, Page } from "@qms/ui";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { HealthPanel } from "../components/HealthPanel";
import { RequireAuth } from "../components/RequireAuth";
import { useAuth } from "../lib/auth";

export default function Home() {
  const { t } = useI18n();
  const { user, logout } = useAuth();
  const router = useRouter();

  async function signOut() {
    await logout();
    router.replace("/signed-out/");
  }

  return (
    <Page>
      <RequireAuth>
        <div className="qms-row">
          <h1 className="qms-heading">{t("app.admin")}</h1>
          <div className="qms-row">
            {user && <span className="qms-muted">{t("auth.signedInAs", { name: user.display_name ?? user.username })}</span>}
            <Button variant="secondary" type="button" onClick={signOut}>
              {t("common.signOut")}
            </Button>
          </div>
        </div>
        {user && <p className="qms-muted">{user.roles.map((role) => t(`roles.${role}`)).join(", ")}</p>}
        {/* A convenience only: the API decides who may configure sites (FR-CFG-103). */}
        {user?.roles.some((role) => role === "system_admin" || role === "org_admin") && <Link href="/sites/">{t("admin.home.sites")}</Link>}
        <HealthPanel />
      </RequireAuth>
    </Page>
  );
}
