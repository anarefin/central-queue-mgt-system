"use client";

import type { NotificationTriggerCatalogueEntry, NotificationTriggerSetting, Site } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Card, ErrorAlert, SelectField } from "@qms/ui";
import { useState } from "react";
import { describeError, localisedName, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

/**
 * Every trigger of SRS §14.2, its enabled state and channel order for the selected Site — or, once a Service is
 * picked, for that Service, overriding the Site's own setting (FR-NTF-010).
 */
export function NotificationTriggersCard({ site }: { site: Site }) {
  const { t, language } = useI18n();
  const { client } = useApi();
  const [serviceId, setServiceId] = useState("");
  const services = useList(client ? () => client.sites.services(site.id) : null, [client, site.id]);
  const catalogue = useList<NotificationTriggerCatalogueEntry>(client ? () => client.notifications.catalogue() : null, [client]);
  const triggers = useList<NotificationTriggerSetting>(
    client ? () => client.notifications.triggers(site.id, serviceId || undefined) : null,
    [client, site.id, serviceId],
  );
  const { busy, error, run } = useSubmit();

  async function toggle(trigger: NotificationTriggerSetting) {
    if (!client) return;
    const ok = await run(() =>
      client.notifications.setTrigger(trigger.trigger_key, site.id, { enabled: !trigger.enabled, channel_order: trigger.channel_order }, serviceId || undefined),
    );
    if (ok) triggers.reload();
  }

  return (
    <Card>
      <h2 className="qms-heading">{t("notifications.triggers.title")}</h2>
      <p className="qms-muted">{t("notifications.triggers.intro")}</p>
      {services.items && services.items.length > 0 && (
        <SelectField
          id="notification-trigger-service"
          label={t("notifications.triggers.scope")}
          value={serviceId}
          onChange={(event) => setServiceId(event.target.value)}
          options={[
            { value: "", label: t("notifications.triggers.scopeSite") },
            ...services.items.map((s) => ({ value: s.id, label: localisedName(s.name_i18n, language, site.default_language) })),
          ]}
        />
      )}
      {(catalogue.error ?? triggers.error) != null && <ErrorAlert>{describeError(t, catalogue.error ?? triggers.error)}</ErrorAlert>}
      {error && <ErrorAlert>{error}</ErrorAlert>}
      <ul className="qms-list">
        {triggers.items?.map((trigger) => {
          const meta = catalogue.items?.find((c) => c.trigger_key === trigger.trigger_key);
          return (
            <li key={trigger.trigger_key} className="qms-row">
              <span>
                {t(`notifications.trigger.${trigger.trigger_key}`)}
                {meta?.essential && ` (${t("notifications.triggers.essential")})`}
              </span>
              <span className="qms-muted">{trigger.channel_order.join(" → ")}</span>
              <span className="qms-muted">
                {trigger.service_overridden
                  ? t("notifications.triggers.overriddenService")
                  : trigger.site_overridden
                    ? t("notifications.triggers.overriddenSite")
                    : t("notifications.triggers.default")}
              </span>
              <button type="button" className="qms-button qms-button--secondary" disabled={busy} onClick={() => void toggle(trigger)}>
                {t(trigger.enabled ? "notifications.triggers.disable" : "notifications.triggers.enable")}
              </button>
            </li>
          );
        })}
      </ul>
    </Card>
  );
}
