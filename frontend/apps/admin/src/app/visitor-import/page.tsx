"use client";

import { useI18n } from "@qms/i18n/react";
import { PageHeader } from "@qms/ui";
import { RequireAuth } from "../../components/RequireAuth";
import { VisitorImportAdmin } from "../../components/VisitorImportAdmin";

export default function VisitorImportPage() {
  const { t } = useI18n();
  return (
    <RequireAuth>
      <div className="mx-auto flex w-full max-w-5xl flex-col gap-6">
        <PageHeader title={t("visitorImport.title")} />
        <VisitorImportAdmin />
      </div>
    </RequireAuth>
  );
}
