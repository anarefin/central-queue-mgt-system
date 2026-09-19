"use client";

import { useI18n } from "@qms/i18n/react";
import { Page } from "@qms/ui";
import Link from "next/link";
import { CatalogueAdmin } from "../../components/CatalogueAdmin";
import { RequireAuth } from "../../components/RequireAuth";

export default function CataloguePage() {
  const { t } = useI18n();
  return (
    <Page>
      <RequireAuth>
        <div className="qms-row">
          <h1 className="qms-heading">{t("catalogue.title")}</h1>
          <Link href="/">{t("sites.back")}</Link>
        </div>
        <CatalogueAdmin />
      </RequireAuth>
    </Page>
  );
}
