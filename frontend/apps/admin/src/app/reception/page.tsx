"use client";

import { useI18n } from "@qms/i18n/react";
import { Page } from "@qms/ui";
import Link from "next/link";
import { ReceptionDesk } from "../../components/ReceptionDesk";
import { RequireAuth } from "../../components/RequireAuth";

export default function ReceptionPage() {
  const { t } = useI18n();
  return (
    <Page>
      <RequireAuth>
        <div className="qms-row">
          <h1 className="qms-heading">{t("reception.title")}</h1>
          <Link href="/">{t("sites.back")}</Link>
        </div>
        <ReceptionDesk />
      </RequireAuth>
    </Page>
  );
}
