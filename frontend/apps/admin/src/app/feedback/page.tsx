"use client";

import { useI18n } from "@qms/i18n/react";
import { Page } from "@qms/ui";
import Link from "next/link";
import { FeedbackAdmin } from "../../components/FeedbackAdmin";
import { RequireAuth } from "../../components/RequireAuth";

export default function FeedbackPage() {
  const { t } = useI18n();
  return (
    <Page>
      <RequireAuth>
        <div className="qms-row">
          <h1 className="qms-heading">{t("admin.home.feedback")}</h1>
          <Link href="/">{t("sites.back")}</Link>
        </div>
        <FeedbackAdmin />
      </RequireAuth>
    </Page>
  );
}
