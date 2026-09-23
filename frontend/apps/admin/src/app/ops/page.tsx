"use client";

import { useI18n } from "@qms/i18n/react";
import { PageHeader } from "@qms/ui";
import { RequireAuth } from "../../components/RequireAuth";
import { DiagnosticsPanel } from "../../components/DiagnosticsPanel";

export default function OpsPage() {
  const { t } = useI18n();
  return (
    <RequireAuth>
      <div className="mx-auto flex w-full max-w-5xl flex-col gap-6">
        <PageHeader title={t("ops.title")} />
        <DiagnosticsPanel />
      </div>
    </RequireAuth>
  );
}
