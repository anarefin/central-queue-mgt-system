"use client";

import type { Counter, Site, Zone } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert } from "@qms/ui";
import { useState } from "react";
import { describeError, languageName, useList } from "../lib/admin-support";
import { useApi } from "../lib/runtime";
import { CounterForm } from "./CounterForm";
import { EntityRow } from "./EntityRow";
import { SiteForm } from "./SiteForm";
import { ZoneForm } from "./ZoneForm";

/**
 * Sites, zones and counters (FR-CFG-001..004): pick a site to see its zones, pick a zone to see its counters. The
 * screen only assembles what the API allows; every call is permission-checked and scoped on the server (FR-CFG-103).
 */
export function SiteAdmin() {
  const { t } = useI18n();
  const { client } = useApi();
  const [siteId, setSiteId] = useState<string | null>(null);

  const sites = useList<Site>(client ? () => client.sites.list() : null, [client]);
  const site = sites.items?.find((s) => s.id === siteId) ?? null;

  return (
    <div className="qms-stack">
      <SitesCard sites={sites} selectedId={siteId} onSelect={setSiteId} />
      {site && <ZonesCard key={site.id} site={site} />}
    </div>
  );
}

function SitesCard({
  sites,
  selectedId,
  onSelect,
}: {
  sites: ReturnType<typeof useList<Site>>;
  selectedId: string | null;
  onSelect: (id: string) => void;
}) {
  const { t, formatDate, formatTime } = useI18n();
  const { client } = useApi();

  return (
    <Card>
      <h2 className="qms-heading">{t("sites.list.title")}</h2>
      {sites.error !== null && <ErrorAlert>{describeError(t, sites.error)}</ErrorAlert>}
      {sites.items?.length === 0 && <p className="qms-muted">{t("sites.list.empty")}</p>}
      {sites.items && sites.items.length > 0 && (
        <ul className="qms-list">
          {sites.items.map((site) => {
            const changed = new Date(site.updated_at);
            return (
              <EntityRow
                key={site.id}
                name={site.name}
                heading={`${site.name} (${site.code})`}
                active={site.active}
                selected={site.id === selectedId}
                lines={[
                  site.address,
                  t("sites.site.languages", {
                    default: languageName(t, site.default_language),
                    enabled: site.enabled_languages.map((code) => languageName(t, code)).join(", "),
                  }),
                  // Timestamps are stored in UTC and shown in the site's own timezone (FR-CFG-002).
                  t("sites.site.changed", {
                    date: formatDate(changed, { timeZone: site.timezone }),
                    time: formatTime(changed, { timeZone: site.timezone }),
                    zone: site.timezone,
                  }),
                ]}
                confirmText={t("sites.site.confirm", { name: site.name })}
                onDeactivate={async () => {
                  await client?.sites.deactivate(site.id);
                  sites.reload();
                }}
                onActivate={async () => {
                  await client?.sites.activate(site.id);
                  sites.reload();
                }}
                extra={
                  <Button variant="secondary" type="button" aria-label={`${t("sites.manageZones")} ${site.name}`} onClick={() => onSelect(site.id)}>
                    {t("sites.manageZones")}
                  </Button>
                }
                editForm={(close) => (
                  <SiteForm
                    initial={site}
                    submitLabel={t("admin.action.save")}
                    onSubmit={async (input) => {
                      await client?.sites.update(site.id, input);
                      sites.reload();
                    }}
                    onDone={close}
                    onCancel={close}
                  />
                )}
              />
            );
          })}
        </ul>
      )}
      <h3 className="qms-heading">{t("sites.add")}</h3>
      <SiteForm
        key={sites.items?.length ?? 0}
        submitLabel={t("sites.add")}
        onSubmit={async (input) => {
          await client?.sites.create(input);
          sites.reload();
        }}
        onDone={() => undefined}
      />
    </Card>
  );
}

/** The zones of one site, and below them the counters of the zone picked here. */
function ZonesCard({ site }: { site: Site }) {
  const { t } = useI18n();
  const { client } = useApi();
  const [zoneId, setZoneId] = useState<string | null>(null);
  // `site.active` is a dependency because deactivating a site also deactivates its zones.
  const zones = useList<Zone>(client ? () => client.sites.zones(site.id) : null, [client, site.id, site.active]);
  const selected = zones.items?.find((zone) => zone.id === zoneId) ?? null;

  return (
    <>
      <Card>
        <h2 className="qms-heading">{t("sites.zones.title", { site: site.name })}</h2>
        {zones.error !== null && <ErrorAlert>{describeError(t, zones.error)}</ErrorAlert>}
        {zones.items?.length === 0 && <p className="qms-muted">{t("sites.zones.empty")}</p>}
        {zones.items && zones.items.length > 0 && (
          <ul className="qms-list">
            {zones.items.map((zone) => (
              <EntityRow
                key={zone.id}
                name={zone.name}
                heading={zone.name}
                active={zone.active}
                selected={zone.id === zoneId}
                lines={[
                  zone.building_label
                    ? t("sites.zone.buildingFloor", { building: zone.building_label, floor: zone.floor_label })
                    : t("sites.zone.floorOnly", { floor: zone.floor_label }),
                ]}
                confirmText={t("sites.zone.confirm", { name: zone.name })}
                onDeactivate={async () => {
                  await client?.zones.deactivate(zone.id);
                  zones.reload();
                }}
                onActivate={async () => {
                  await client?.zones.activate(zone.id);
                  zones.reload();
                }}
                extra={
                  <Button variant="secondary" type="button" aria-label={`${t("sites.manageCounters")} ${zone.name}`} onClick={() => setZoneId(zone.id)}>
                    {t("sites.manageCounters")}
                  </Button>
                }
                editForm={(close) => (
                  <ZoneForm
                    initial={{ name: zone.name, floor_label: zone.floor_label, building_label: zone.building_label ?? "" }}
                    submitLabel={t("admin.action.save")}
                    onSubmit={async (input) => {
                      await client?.zones.update(zone.id, input);
                      zones.reload();
                    }}
                    onDone={close}
                    onCancel={close}
                  />
                )}
              />
            ))}
          </ul>
        )}
        <h3 className="qms-heading">{t("sites.zones.add")}</h3>
        <ZoneForm
          key={zones.items?.length ?? 0}
          submitLabel={t("sites.zones.add")}
          onSubmit={async (input) => {
            await client?.sites.createZone(site.id, input);
            zones.reload();
          }}
          onDone={() => undefined}
        />
      </Card>
      {selected && <CountersCard key={selected.id} zone={selected} />}
    </>
  );
}

function CountersCard({ zone }: { zone: Zone }) {
  const { t } = useI18n();
  const { client } = useApi();
  const counters = useList<Counter>(client ? () => client.zones.counters(zone.id) : null, [client, zone.id, zone.active]);

  return (
    <Card>
      <h2 className="qms-heading">{t("sites.counters.title", { zone: zone.name })}</h2>
      {counters.error !== null && <ErrorAlert>{describeError(t, counters.error)}</ErrorAlert>}
      {counters.items?.length === 0 && <p className="qms-muted">{t("sites.counters.empty")}</p>}
      {counters.items && counters.items.length > 0 && (
        <ul className="qms-list">
          {counters.items.map((counter) => (
            <EntityRow
              key={counter.id}
              name={counter.label}
              heading={counter.label}
              active={counter.active}
              lines={counter.location_note ? [t("sites.counter.note", { note: counter.location_note })] : []}
              confirmText={t("sites.counter.confirm", { name: counter.label })}
              onDeactivate={async () => {
                await client?.counters.deactivate(counter.id);
                counters.reload();
              }}
              onActivate={async () => {
                await client?.counters.activate(counter.id);
                counters.reload();
              }}
              editForm={(close) => (
                <CounterForm
                  initial={{ label: counter.label, location_note: counter.location_note ?? "" }}
                  submitLabel={t("admin.action.save")}
                  onSubmit={async (input) => {
                    await client?.counters.update(counter.id, input);
                    counters.reload();
                  }}
                  onDone={close}
                  onCancel={close}
                />
              )}
            />
          ))}
        </ul>
      )}
      <h3 className="qms-heading">{t("sites.counters.add")}</h3>
      <CounterForm
        key={counters.items?.length ?? 0}
        submitLabel={t("sites.counters.add")}
        onSubmit={async (input) => {
          await client?.zones.createCounter(zone.id, input);
          counters.reload();
        }}
        onDone={() => undefined}
      />
    </Card>
  );
}
