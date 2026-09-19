"use client";

import type { Site } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { ErrorAlert, SelectField } from "@qms/ui";
import { useState } from "react";
import { describeError, useList } from "../lib/admin-support";
import { useApi } from "../lib/runtime";
import { DryRunCard } from "./DryRunCard";
import { PriorityClassesCard } from "./PriorityClassesCard";
import { StrategyCard } from "./StrategyCard";

/**
 * Priority and queue ordering (FR-QUE-010, FR-QUE-021, FR-QUE-023): the Priority classes of the organisation, then, for
 * one site, the ordering strategy of each Service group and the dry run of a service's queue. Every call is
 * permission-checked and scoped on the server (FR-CFG-103).
 */
export function PriorityAdmin() {
  const { t } = useI18n();
  const { client } = useApi();
  const [siteId, setSiteId] = useState("");
  const sites = useList<Site>(client ? () => client.sites.list() : null, [client]);
  const site = sites.items?.find((s) => s.id === siteId) ?? sites.items?.[0] ?? null;

  return (
    <div className="qms-stack">
      <p className="qms-muted">{t("priority.intro")}</p>
      <PriorityClassesCard />
      {sites.error !== null && <ErrorAlert>{describeError(t, sites.error)}</ErrorAlert>}
      {sites.items?.length === 0 && <p className="qms-muted">{t("catalogue.site.none")}</p>}
      {sites.items && sites.items.length > 1 && (
        <SelectField
          id="priority-site"
          label={t("catalogue.site.pick")}
          value={site?.id ?? ""}
          onChange={(event) => setSiteId(event.target.value)}
          options={sites.items.map((s) => ({ value: s.id, label: `${s.name} (${s.code})` }))}
        />
      )}
      {site && <StrategyCard key={`strategy-${site.id}`} site={site} />}
      {site && <DryRunCard key={`dryrun-${site.id}`} site={site} />}
    </div>
  );
}
