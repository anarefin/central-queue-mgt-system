"use client";

import { useI18n } from "@qms/i18n/react";
import { PageHeader } from "@qms/ui";
import { RequireAuth } from "../../components/RequireAuth";
import { CatalogueAdmin } from "../../components/CatalogueAdmin";

export default function CataloguePage() {
  const { t } = useI18n();
  return (
    <RequireAuth>
      <div className="mx-auto flex w-full max-w-5xl flex-col gap-6">
        <PageHeader title={t("catalogue.title")} />
        <CatalogueAdmin />
      </div>
    </RequireAuth>
  );
}
