"use client";

import type { ServiceGroup, Site } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField } from "@qms/ui";
import { useState } from "react";
import { describeError, languageName, localisedName, useList } from "../lib/admin-support";
import { useApi } from "../lib/runtime";
import { EntityRow } from "./EntityRow";
import { ServiceGroupForm } from "./ServiceGroupForm";
import { ServicesCard } from "./ServicesCard";
import { TeamCard } from "./TeamCard";

/**
 * The service catalogue (FR-CFG-010..015): pick a site, then a service group, then its services or its team; a
 * service opens its counters and outcome codes. The screen only assembles what the API allows; every call is
 * permission-checked and scoped on the server (FR-CFG-103).
 */
export function CatalogueAdmin() {
  const { t } = useI18n();
  const { client } = useApi();
  const [siteId, setSiteId] = useState<string>("");

  const sites = useList<Site>(client ? () => client.sites.list() : null, [client]);
  const site = sites.items?.find((s) => s.id === siteId) ?? sites.items?.[0] ?? null;

  return (
    <div className="qms-stack">
      {sites.error !== null && <ErrorAlert>{describeError(t, sites.error)}</ErrorAlert>}
      {sites.items?.length === 0 && <p className="qms-muted">{t("catalogue.site.none")}</p>}
      {sites.items && sites.items.length > 1 && (
        <SelectField
          id="catalogue-site"
          label={t("catalogue.site.pick")}
          value={site?.id ?? ""}
          onChange={(event) => setSiteId(event.target.value)}
          options={sites.items.map((s) => ({ value: s.id, label: `${s.name} (${s.code})` }))}
        />
      )}
      {site && <GroupsCard key={site.id} site={site} />}
    </div>
  );
}

type GroupPanel = "services" | "team";

function GroupsCard({ site }: { site: Site }) {
  const { t, language } = useI18n();
  const { client } = useApi();
  const [selected, setSelected] = useState<{ id: string; panel: GroupPanel } | null>(null);
  const groups = useList<ServiceGroup>(client ? () => client.catalogue.groups(site.id) : null, [client, site.id]);
  const group = groups.items?.find((g) => g.id === selected?.id) ?? null;
  const nameOf = (g: ServiceGroup) => localisedName(g.name_i18n, language, site.default_language);

  return (
    <>
      <Card>
        <h2 className="qms-heading">{t("catalogue.groups.title", { site: site.name })}</h2>
        {groups.error !== null && <ErrorAlert>{describeError(t, groups.error)}</ErrorAlert>}
        {groups.items?.length === 0 && <p className="qms-muted">{t("catalogue.groups.empty")}</p>}
        {groups.items && groups.items.length > 0 && (
          <ul className="qms-list">
            {groups.items.map((g) => (
              <EntityRow
                key={g.id}
                name={nameOf(g)}
                heading={nameOf(g)}
                active={g.active}
                selected={g.id === selected?.id}
                lines={[t("catalogue.group.line", { prefix: g.token_prefix, order: g.display_order })]}
                warnings={
                  g.missing_translations.length > 0
                    ? [t("catalogue.names.missingRow", { languages: g.missing_translations.map((l) => languageName(t, l)).join(", ") })]
                    : []
                }
                confirmText={t("catalogue.group.confirm", { name: nameOf(g) })}
                onDeactivate={async () => {
                  await client?.catalogue.deactivateGroup(g.id);
                  groups.reload();
                }}
                onActivate={async () => {
                  await client?.catalogue.activateGroup(g.id);
                  groups.reload();
                }}
                extra={
                  <>
                    <Button
                      variant="secondary"
                      type="button"
                      aria-label={`${t("catalogue.manageServices")} ${nameOf(g)}`}
                      onClick={() => setSelected({ id: g.id, panel: "services" })}
                    >
                      {t("catalogue.manageServices")}
                    </Button>
                    <Button
                      variant="secondary"
                      type="button"
                      aria-label={`${t("catalogue.manageTeam")} ${nameOf(g)}`}
                      onClick={() => setSelected({ id: g.id, panel: "team" })}
                    >
                      {t("catalogue.manageTeam")}
                    </Button>
                  </>
                }
                editForm={(close) => (
                  <ServiceGroupForm
                    site={site}
                    initial={g}
                    submitLabel={t("admin.action.save")}
                    onSubmit={async (input) => {
                      await client?.catalogue.updateGroup(g.id, input);
                      groups.reload();
                    }}
                    onDone={close}
                    onCancel={close}
                  />
                )}
              />
            ))}
          </ul>
        )}
        <h3 className="qms-heading">{t("catalogue.groups.add")}</h3>
        <ServiceGroupForm
          key={groups.items?.length ?? 0}
          site={site}
          submitLabel={t("catalogue.groups.add")}
          onSubmit={async (input) => {
            await client?.catalogue.createGroup(site.id, input);
            groups.reload();
          }}
          onDone={() => undefined}
        />
      </Card>
      {group && selected?.panel === "services" && <ServicesCard key={group.id} site={site} group={group} />}
      {group && selected?.panel === "team" && <TeamCard key={group.id} group={group} groupName={nameOf(group)} />}
    </>
  );
}
