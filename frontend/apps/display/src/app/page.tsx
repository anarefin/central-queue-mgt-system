"use client";

import { useI18n } from "@qms/i18n/react";
import { Card, Page } from "@qms/ui";

export default function Home() {
  const { t } = useI18n();
  return (
    <Page>
      <Card>
        <h1 className="qms-heading">{t("app.display")}</h1>
        <p className="qms-muted">{t("placeholder.comingSoon")}</p>
      </Card>
    </Page>
  );
}
