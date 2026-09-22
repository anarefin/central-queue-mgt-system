"use client";

import { useI18n } from "@qms/i18n/react";
import { Page } from "@qms/ui";
import Link from "next/link";
import { RequireAuth } from "../../components/RequireAuth";
import { PrivacyControlsCard } from "../../components/PrivacyControlsCard";

export default function PrivacyPage() {
  const { t } = useI18n();
  return (
    <Page>
      <RequireAuth>
        <div className="qms-row">
          <h1 className="qms-heading">{t("privacy.title")}</h1>
          <Link href="/">{t("privacy.back")}</Link>
        </div>
        <PrivacyControlsCard />
      </RequireAuth>
    </Page>
  );
}
