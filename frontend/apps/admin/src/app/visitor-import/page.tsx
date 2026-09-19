"use client";

import { useI18n } from "@qms/i18n/react";
import { Page } from "@qms/ui";
import Link from "next/link";
import { RequireAuth } from "../../components/RequireAuth";
import { VisitorImportAdmin } from "../../components/VisitorImportAdmin";

export default function VisitorImportPage() {
  const { t } = useI18n();
  return (
    <Page>
      <RequireAuth>
        <div className="qms-row">
          <h1 className="qms-heading">{t("visitorImport.title")}</h1>
          <Link href="/">{t("sites.back")}</Link>
        </div>
        <VisitorImportAdmin />
      </RequireAuth>
    </Page>
  );
}
