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
        {user?.roles.some((role) => role === "system_admin" || role === "org_admin") && (
          <div className="qms-row">
            <Link href="/setup/">{t("admin.home.setup")}</Link>
            <Link href="/sites/">{t("admin.home.sites")}</Link>
            <Link href="/devices/">{t("admin.home.devices")}</Link>
            <Link href="/catalogue/">{t("admin.home.catalogue")}</Link>
            <Link href="/numbering/">{t("admin.home.numbering")}</Link>
            <Link href="/priority/">{t("admin.home.priority")}</Link>
            <Link href="/breaks/">{t("admin.home.breaks")}</Link>
            <Link href="/visitor-import/">{t("admin.home.visitorImport")}</Link>
            <Link href="/branding/">{t("admin.home.branding")}</Link>
            <Link href="/notifications/">{t("admin.home.notifications")}</Link>
            <Link href="/privacy/">{t("admin.home.privacy")}</Link>
            <Link href="/webhooks/">{t("admin.home.webhooks")}</Link>
          </div>
        )}
        {/* Diagnostics carries a config snapshot, so it is narrower than the rest of this admin-only row (System
            Administrator only, `ops:diagnostics_export`); the API decides for real. */}
        {user?.roles.includes("system_admin") && (
          <div className="qms-row">
            <Link href="/ops/">{t("admin.home.ops")}</Link>
          </div>
        )}
        {user?.roles.some((role) => role === "system_admin" || role === "org_admin" || role === "team_admin") && (
          <div className="qms-row">
            <Link href="/availability/">{t("admin.home.availability")}</Link>
            <Link href="/notice-board/">{t("admin.home.noticeBoard")}</Link>
            <Link href="/reports/">{t("admin.home.reports")}</Link>
          </div>
        )}
        {/* Feedback comment approval is a Team Admin action (FR-MOB-033); the API is the one that actually checks it. */}
        {user?.roles.includes("team_admin") && (
          <div className="qms-row">
            <Link href="/feedback/">{t("admin.home.feedback")}</Link>
          </div>
        )}
        {user?.roles.includes("reception_operator") && (
          <div className="qms-row">
            <Link href="/reception/">{t("admin.home.reception")}</Link>
          </div>
        )}
        <HealthPanel />
      </RequireAuth>
    </Page>
  );
}
