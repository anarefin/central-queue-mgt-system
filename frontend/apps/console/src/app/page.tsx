"use client";

import { useI18n } from "@qms/i18n/react";
import { Button, Page } from "@qms/ui";
import { useRouter } from "next/navigation";
import { CounterConsole } from "../components/CounterConsole";
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
          <h1 className="qms-heading">{t("console.title")}</h1>
          <div className="qms-row">
            {user && <span className="qms-muted">{t("auth.signedInAs", { name: user.display_name ?? user.username })}</span>}
            <Button variant="secondary" type="button" onClick={() => router.push("/dashboard/")}>
              {t("dashboard.title")}
            </Button>
            <Button variant="secondary" type="button" onClick={signOut}>
              {t("common.signOut")}
            </Button>
          </div>
        </div>
        <CounterConsole />
      </RequireAuth>
    </Page>
  );
}
