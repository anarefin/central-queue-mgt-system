"use client";

import { useI18n } from "@qms/i18n/react";
import { Page } from "@qms/ui";
import Link from "next/link";
import { BrandingAdmin } from "../../components/BrandingAdmin";
import { RequireAuth } from "../../components/RequireAuth";

export default function BrandingAdminPage() {
  const { t } = useI18n();
  return (
    <Page>
      <RequireAuth>
        <div className="qms-row">
          <h1 className="qms-heading">{t("branding.title")}</h1>
          <Link href="/">{t("sites.back")}</Link>
        </div>
        <BrandingAdmin />
      </RequireAuth>
    </Page>
  );
}
