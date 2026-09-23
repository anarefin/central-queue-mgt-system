"use client";

import type { NumberingRule, ServiceGroup, Site, SiteServices } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Card, ErrorAlert, SelectField } from "@qms/ui";
import { useState } from "react";
import { describeError, localisedName, useList } from "../lib/admin-support";
import { useApi } from "../lib/runtime";
import { NumberingScopeRow } from "./NumberingScopeRow";

type OfferedService = SiteServices["items"][number];

/**
 * Token numbering (FR-CFG-018): pick a site, then set a rule for a service group or for one service, and preview what
 * the next Token number would be. Every call is permission-checked and scoped on the server (FR-CFG-103).
 */
export function NumberingAdmin() {
  const { t } = useI18n();
  const { client } = useApi();
  const [siteId, setSiteId] = useState<string>("");

  const sites = useList<Site>(client ? () => client.sites.list() : null, [client]);
  const site = sites.items?.find((s) => s.id === siteId) ?? sites.items?.[0] ?? null;

  return (
    <div className="flex flex-col gap-4">
      {sites.error !== null && <ErrorAlert>{describeError(t, sites.error)}</ErrorAlert>}
      {sites.items?.length === 0 && <p className="text-fg-muted">{t("catalogue.site.none")}</p>}
      {sites.items && sites.items.length > 1 && (
        <SelectField
          id="numbering-site"
          label={t("catalogue.site.pick")}
          value={site?.id ?? ""}
          onChange={(event) => setSiteId(event.target.value)}
          options={sites.items.map((s) => ({ value: s.id, label: `${s.name} (${s.code})` }))}
        />
      )}
      {site && <SiteNumbering key={site.id} site={site} />}
    </div>
  );
}

function SiteNumbering({ site }: { site: Site }) {
  const { t, language } = useI18n();
  const { client } = useApi();
  const groups = useList<ServiceGroup>(client ? () => client.catalogue.groups(site.id) : null, [client, site.id]);
  const services = useList<OfferedService>(client ? () => client.sites.services(site.id) : null, [client, site.id]);
  const rules = useList<NumberingRule>(client ? () => client.numbering.rules(site.id) : null, [client, site.id]);

  const ruleOf = (scope: NumberingRule["scope_type"], id: string) => rules.items?.find((r) => r.scope_type === scope && r.scope_id === id);
  const serviceNames = Object.fromEntries((services.items ?? []).map((s) => [s.id, localisedName(s.name_i18n, language, site.default_language)]));
  const error = groups.error ?? services.error ?? rules.error;

  return (
    <Card>
      <h2 className="font-semibold text-fg">{t("numbering.groups.title", { site: site.name })}</h2>
      <p className="text-fg-muted">{t("numbering.intro")}</p>
      {error !== null && <ErrorAlert>{describeError(t, error)}</ErrorAlert>}
      {groups.items?.length === 0 && <p className="text-fg-muted">{t("catalogue.groups.empty")}</p>}
      {groups.items && rules.items && (
        <ul className="m-0 list-none p-0 flex flex-col divide-y divide-border [&>li]:flex [&>li]:flex-wrap [&>li]:items-center [&>li]:justify-between [&>li]:gap-2 [&>li]:py-2.5">
          {groups.items.map((group) => {
            const groupName = localisedName(group.name_i18n, language, site.default_language);
            const own = (services.items ?? []).filter((s) => s.service_group.id === group.id);
            return (
              <li key={group.id}>
                <div className="flex flex-col gap-4 flex-1 min-w-0">
                  <ul className="m-0 list-none p-0 flex flex-col divide-y divide-border [&>li]:flex [&>li]:flex-wrap [&>li]:items-center [&>li]:justify-between [&>li]:gap-2 [&>li]:py-2.5">
                    <NumberingScopeRow
                      scope="service_group"
                      id={group.id}
                      name={groupName}
                      rule={ruleOf("service_group", group.id)}
                      fallback="default"
                      serviceNames={serviceNames}
                      onChanged={rules.reload}
                    />
                    {own.map((service) => (
                      <NumberingScopeRow
                        key={service.id}
                        scope="service"
                        id={service.id}
                        name={serviceNames[service.id] ?? service.token_prefix}
                        rule={ruleOf("service", service.id)}
                        fallback={ruleOf("service_group", group.id) ? "service_group" : "default"}
                        serviceNames={serviceNames}
                        onChanged={rules.reload}
                      />
                    ))}
                  </ul>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}
