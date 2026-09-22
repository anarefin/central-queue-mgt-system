"use client";

import { useI18n } from "@qms/i18n/react";
import { Page } from "@qms/ui";
import Link from "next/link";
import { RequireAuth } from "../../components/RequireAuth";
import { SetupWizard } from "../../components/SetupWizard";

export default function SetupWizardPage() {
  const { t } = useI18n();
  return (
    <Page>
      <RequireAuth>
        <div className="qms-row">
          <h1 className="qms-heading">{t("setup.title")}</h1>
          <Link href="/">{t("sites.back")}</Link>
        </div>
        <SetupWizard />
      </RequireAuth>
    </Page>
  );
}
