"use client";

import { useI18n } from "@qms/i18n/react";
import { Page } from "@qms/ui";
import Link from "next/link";
import { RequireAuth } from "../../components/RequireAuth";
import { DeviceAdmin } from "../../components/DeviceAdmin";

export default function DevicesPage() {
  const { t } = useI18n();
  return (
    <Page>
      <RequireAuth>
        <div className="qms-row">
          <h1 className="qms-heading">{t("devices.title")}</h1>
          <Link href="/">{t("devices.back")}</Link>
        </div>
        <DeviceAdmin />
      </RequireAuth>
    </Page>
  );
}
