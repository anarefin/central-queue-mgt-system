"use client";

import type { Site } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { ErrorAlert, SelectField } from "@qms/ui";
import { useState } from "react";
import { describeError, useList } from "../lib/admin-support";
import { useApi } from "../lib/runtime";
import { NotificationLogCard } from "./NotificationLogCard";
import { NotificationTemplatesCard } from "./NotificationTemplatesCard";
import { NotificationTriggersCard } from "./NotificationTriggersCard";

/**
 * The notification pipeline's admin surface (ticket 38, SRS §14): trigger settings per Site and Service
 * (FR-NTF-010), templates per trigger x channel x language (FR-NTF-020, FR-NTF-021), and the delivery log
 * (FR-NTF-032). Every call is permission-checked and scoped on the server (FR-CFG-103).
 */
export function NotificationsAdmin() {
  const { t } = useI18n();
  const { client } = useApi();
  const [siteId, setSiteId] = useState("");
  const sites = useList<Site>(client ? () => client.sites.list() : null, [client]);
  const site = sites.items?.find((s) => s.id === siteId) ?? sites.items?.[0] ?? null;

  return (
    <div className="qms-stack">
      <p className="qms-muted">{t("notifications.intro")}</p>
      {sites.error !== null && <ErrorAlert>{describeError(t, sites.error)}</ErrorAlert>}
      {sites.items?.length === 0 && <p className="qms-muted">{t("catalogue.site.none")}</p>}
      {sites.items && sites.items.length > 1 && (
        <SelectField
          id="notifications-site"
          label={t("catalogue.site.pick")}
          value={site?.id ?? ""}
          onChange={(event) => setSiteId(event.target.value)}
          options={sites.items.map((s) => ({ value: s.id, label: `${s.name} (${s.code})` }))}
        />
      )}
      {site && <NotificationTriggersCard key={`triggers-${site.id}`} site={site} />}
      <NotificationTemplatesCard />
      <NotificationLogCard />
    </div>
  );
}
