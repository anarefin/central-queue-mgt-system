"use client";

import type { Site } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { ErrorAlert, SelectField } from "@qms/ui";
import { useState } from "react";
import { describeError, useList } from "../lib/admin-support";
import { useApi } from "../lib/runtime";
import { DetailedTokenReportCard } from "./DetailedTokenReportCard";
import { DomainReportsCard } from "./DomainReportsCard";
import { OperationalReportsCard } from "./OperationalReportsCard";
import { PlanningViewsCard } from "./PlanningViewsCard";
import { ReportScheduleCard } from "./ReportScheduleCard";
import { RetentionPolicyCard } from "./RetentionPolicyCard";

/** Reports (SRS §16, ticket 48): picks the Site, then the detailed token report for it. Every call is
 * permission-checked and scoped on the server (`reports:run_export`, FR-CFG-106). */
export function ReportsAdmin() {
  const { t } = useI18n();
  const { client } = useApi();
  const [siteId, setSiteId] = useState("");
  const sites = useList<Site>(client ? () => client.sites.list() : null, [client]);
  const site = sites.items?.find((s) => s.id === siteId) ?? sites.items?.[0] ?? null;

  return (
    <div className="flex flex-col gap-4">
      <p className="text-fg-muted">{t("reports.intro")}</p>
      {sites.error !== null && <ErrorAlert>{describeError(t, sites.error)}</ErrorAlert>}
      {sites.items?.length === 0 && <p className="text-fg-muted">{t("catalogue.site.none")}</p>}
      {sites.items && sites.items.length > 1 && (
        <SelectField
          id="reports-site"
          label={t("catalogue.site.pick")}
          value={site?.id ?? ""}
          onChange={(event) => setSiteId(event.target.value)}
          options={sites.items.map((s) => ({ value: s.id, label: `${s.name} (${s.code})` }))}
        />
      )}
      {site && <DetailedTokenReportCard key={`report-${site.id}`} site={site} />}
      {site && <OperationalReportsCard key={`operational-report-${site.id}`} site={site} />}
      {site && <DomainReportsCard key={`domain-report-${site.id}`} site={site} />}
      {site && <PlanningViewsCard key={`planning-view-${site.id}`} site={site} />}
      {site && <ReportScheduleCard key={`report-schedule-${site.id}`} site={site} />}
      <RetentionPolicyCard />
    </div>
  );
}
