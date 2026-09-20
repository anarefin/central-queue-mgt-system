"use client";

import { useI18n } from "@qms/i18n/react";
import { Page } from "@qms/ui";
import Link from "next/link";
import { RequireAuth } from "../../components/RequireAuth";
import { NoticeBoardAdmin } from "../../components/NoticeBoardAdmin";

export default function NoticeBoardPage() {
  const { t } = useI18n();
  return (
    <Page>
      <RequireAuth>
        <div className="qms-row">
          <h1 className="qms-heading">{t("noticeBoard.title")}</h1>
          <Link href="/">{t("noticeBoard.back")}</Link>
        </div>
        <NoticeBoardAdmin />
      </RequireAuth>
    </Page>
  );
}
