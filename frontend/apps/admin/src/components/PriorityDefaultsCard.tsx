"use client";

import { CHANNELS, type Channel, type PriorityClass, type PriorityDefaults, type Site, type SiteServices } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField } from "@qms/ui";
import { useEffect, useState } from "react";
import { describeError, localisedName, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

/**
 * Where a new ticket's Priority class comes from when staff choose none (FR-QUE-011): a default per issuing channel and one per
 * Service of the site. A default applies to tickets issued from then on and never moves one already issued (FR-CFG-041), which the
 * screen says. Every call is permission-checked and scoped on the server (FR-CFG-103).
 */
export function PriorityDefaultsCard({ site }: { site: Site }) {
  const { t, language } = useI18n();
  const { client } = useApi();
  const [classes, setClasses] = useState<PriorityClass[] | null>(null);
  const [defaults, setDefaults] = useState<PriorityDefaults | null>(null);
  const [services, setServices] = useState<SiteServices | null>(null);
  const [loadError, setLoadError] = useState<unknown>(null);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    Promise.all([client.priority.classes(), client.priority.defaults(), client.sites.services(site.id)]).then(
      ([loadedClasses, loadedDefaults, loadedServices]) => {
        if (cancelled) return;
        setClasses(loadedClasses.items);
        setDefaults(loadedDefaults);
        setServices(loadedServices);
      },
      (cause: unknown) => !cancelled && setLoadError(cause),
    );
    return () => {
      cancelled = true;
    };
  }, [client, site.id]);

  const nameOf = (names: Record<string, string>) => localisedName(names, language, site.default_language);
  // The normal class is what no default means, so it is not offered as one; a switched-off class cannot be given to new tickets.
  const offered = (classes ?? []).filter((c) => !c.is_default && c.active);
  const options = [{ value: "", label: t("priority.defaults.none") }, ...offered.map((c) => ({ value: c.id, label: nameOf(c.name_i18n) }))];

  return (
    <Card>
      <h2 className="qms-heading">{t("priority.defaults.title")}</h2>
      <p className="qms-muted">{t("priority.defaults.intro")}</p>
      {loadError !== null && <ErrorAlert>{describeError(t, loadError)}</ErrorAlert>}
      {classes && defaults && services && (
        <>
          <h3 className="qms-label">{t("priority.defaults.channels")}</h3>
          <ul className="qms-list">
            {CHANNELS.map((channel: Channel) => (
              <DefaultRow
                key={channel}
                id={`default-channel-${channel}`}
                name={t(`catalogue.channel.${channel}`)}
                label={t("priority.defaults.channel", { channel: t(`catalogue.channel.${channel}`) })}
                current={defaults.channels.find((c) => c.channel === channel)?.priority_class_id ?? null}
                options={options}
                save={async (classId) => {
                  const saved = await client!.priority.setChannelDefault(channel, classId);
                  setDefaults((d) => d && { ...d, channels: d.channels.map((c) => (c.channel === channel ? saved : c)) });
                }}
              />
            ))}
          </ul>
          <h3 className="qms-label">{t("priority.defaults.services")}</h3>
          {services.items.length === 0 && <p className="qms-muted">{t("priority.defaults.noServices")}</p>}
          <ul className="qms-list">
            {services.items.map((service) => (
              <DefaultRow
                key={service.id}
                id={`default-service-${service.id}`}
                name={nameOf(service.name_i18n)}
                label={t("priority.defaults.service", { service: nameOf(service.name_i18n) })}
                current={defaults.services.find((s) => s.service_id === service.id)?.priority_class_id ?? null}
                options={options}
                save={async (classId) => {
                  const saved = await client!.priority.setServiceDefault(service.id, classId);
                  setDefaults((d) => d && { ...d, services: [...d.services.filter((s) => s.service_id !== service.id), ...(saved.priority_class_id ? [saved] : [])] });
                }}
              />
            ))}
          </ul>
        </>
      )}
    </Card>
  );
}

function DefaultRow({
  id,
  name,
  label,
  current,
  options,
  save,
}: {
  id: string;
  name: string;
  label: string;
  /** The class saved now, null for none. */
  current: string | null;
  options: { value: string; label: string }[];
  save: (classId: string | null) => Promise<void>;
}) {
  const { t } = useI18n();
  const [chosen, setChosen] = useState(current ?? "");
  const [saved, setSaved] = useState(false);
  const { busy, error, run } = useSubmit();

  async function submit() {
    setSaved(await run(() => save(chosen === "" ? null : chosen)));
  }

  return (
    <li>
      <div className="qms-stack qms-grow">
        <strong>{name}</strong>
        <SelectField
          id={id}
          label={label}
          value={chosen}
          onChange={(event) => {
            setChosen(event.target.value);
            setSaved(false);
          }}
          options={options}
        />
        <div className="qms-row">
          <Button type="button" aria-label={`${t("priority.defaults.save")} ${name}`} disabled={busy || chosen === (current ?? "")} onClick={submit}>
            {t("priority.defaults.save")}
          </Button>
        </div>
        {error && <ErrorAlert>{error}</ErrorAlert>}
        {saved && (
          <span className="qms-muted" role="status">
            {t("priority.defaults.saved")}
          </span>
        )}
      </div>
    </li>
  );
}
