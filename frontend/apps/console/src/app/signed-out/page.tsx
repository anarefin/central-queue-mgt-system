"use client";

import { useI18n } from "@qms/i18n/react";
import { Button, Card, Page } from "@qms/ui";
import { useRouter } from "next/navigation";

export default function SignedOutPage() {
  const { t } = useI18n();
  const router = useRouter();
  return (
    <Page>
      <Card>
        <h1 className="text-lg font-semibold text-fg">{t("auth.signedOutTitle")}</h1>
        <Button type="button" onClick={() => router.replace("/login/")}>
          {t("auth.signInAgain")}
        </Button>
      </Card>
    </Page>
  );
}
