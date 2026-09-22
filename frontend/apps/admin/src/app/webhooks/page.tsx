"use client";

import { useI18n } from "@qms/i18n/react";
import { Page } from "@qms/ui";
import Link from "next/link";
import { RequireAuth } from "../../components/RequireAuth";
import { WebhooksAdmin } from "../../components/WebhooksAdmin";

export default function WebhooksPage() {
  const { t } = useI18n();
  return (
    <Page>
      <RequireAuth>
        <div className="qms-row">
          <h1 className="qms-heading">{t("webhooks.title")}</h1>
          <Link href="/">{t("sites.back")}</Link>
        </div>
        <WebhooksAdmin />
      </RequireAuth>
    </Page>
  );
}
