"use client";

import { useI18n } from "@qms/i18n/react";
import { Page } from "@qms/ui";
import Link from "next/link";
import { NumberingAdmin } from "../../components/NumberingAdmin";
import { RequireAuth } from "../../components/RequireAuth";

export default function NumberingPage() {
  const { t } = useI18n();
  return (
    <Page>
      <RequireAuth>
        <div className="qms-row">
          <h1 className="qms-heading">{t("numbering.title")}</h1>
          <Link href="/">{t("sites.back")}</Link>
        </div>
        <NumberingAdmin />
      </RequireAuth>
    </Page>
  );
}
