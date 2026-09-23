"use client";

import { useI18n } from "@qms/i18n/react";
import { Card, ErrorAlert, Page } from "@qms/ui";
import { VisitorDashboard } from "../../components/VisitorDashboard";
import { VisitorLogin } from "../../components/VisitorLogin";
import { useApi } from "../../lib/runtime";
import { useAccount } from "../../lib/visitorAuth";

/** A registered visitor's own sign-in and "my account" screen (ticket 41, FR-MOB-001, FR-MOB-002): the email + OTP
 * login, and — once signed in — active tickets, appointment history, saved sites, and self-service reschedule and
 * cancel. Entirely separate from the home page's anonymous, ticket-secret-only status view (ticket 37). */
export default function AccountPage() {
  const { t } = useI18n();
  const { error: configError } = useApi();
  const { status } = useAccount();

  if (configError) {
    return (
      <Page>
        <Card>
          <ErrorAlert>{t("account.error.generic")}</ErrorAlert>
        </Card>
      </Page>
    );
  }

  if (status === "unknown") {
    return (
      <Page>
        <Card>
          <p className="text-fg-muted">{t("common.loading")}</p>
        </Card>
      </Page>
    );
  }

  return <Page>{status === "authenticated" ? <VisitorDashboard /> : <VisitorLogin />}</Page>;
}
