"use client";

import { QUEUE_STRATEGIES, type QueueStrategy, type RoutingStrategy, type ServiceGroup, type Site } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField } from "@qms/ui";
import { useEffect, useState } from "react";
import { describeError, localisedName, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";
import { VersionHistory } from "./VersionHistory";

/** The ordering strategy of each Service group of a site (FR-QUE-021). */
export function StrategyCard({ site }: { site: Site }) {
  const { t, language } = useI18n();
  const { client } = useApi();
  const groups = useList<ServiceGroup>(client ? () => client.catalogue.groups(site.id) : null, [client, site.id]);

  return (
    <Card>
      <h2 className="font-semibold text-fg">{t("priority.strategy.title")}</h2>
      <p className="text-fg-muted">{t("priority.strategy.intro")}</p>
      {groups.error !== null && <ErrorAlert>{describeError(t, groups.error)}</ErrorAlert>}
      {groups.items?.length === 0 && <p className="text-fg-muted">{t("catalogue.groups.empty")}</p>}
      <ul className="m-0 list-none p-0 flex flex-col divide-y divide-border [&>li]:flex [&>li]:flex-wrap [&>li]:items-center [&>li]:justify-between [&>li]:gap-2 [&>li]:py-2.5">
        {groups.items?.map((group) => (
          <GroupStrategy key={group.id} group={group} name={localisedName(group.name_i18n, language, site.default_language)} />
        ))}
      </ul>
    </Card>
  );
}

function GroupStrategy({ group, name }: { group: ServiceGroup; name: string }) {
  const { t } = useI18n();
  const { client } = useApi();
  const [current, setCurrent] = useState<RoutingStrategy | null>(null);
  const [chosen, setChosen] = useState<QueueStrategy>("weighted_wait");
  const [saved, setSaved] = useState(false);
  const [loadError, setLoadError] = useState<unknown>(null);
  const { busy, error, run } = useSubmit();

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client.priority.strategy(group.id).then(
      (strategy) => {
        if (cancelled) return;
        setCurrent(strategy);
        setChosen(strategy.strategy);
      },
      (cause: unknown) => !cancelled && setLoadError(cause),
    );
    return () => {
      cancelled = true;
    };
  }, [client, group.id]);

  async function save() {
    const done = await run(async () => {
      const updated = await client!.priority.setStrategy(group.id, chosen);
      setCurrent(updated);
      setChosen(updated.strategy);
    });
    setSaved(done);
  }

  return (
    <li>
      <div className="flex flex-col gap-4 flex-1 min-w-0">
        <strong>{name}</strong>
        {loadError !== null && <ErrorAlert>{describeError(t, loadError)}</ErrorAlert>}
        {current?.is_default && <span className="text-fg-muted">{t("priority.strategy.default")}</span>}
        <SelectField
          id={`strategy-${group.id}`}
          label={t("priority.strategy.group", { group: name })}
          value={chosen}
          disabled={current === null}
          onChange={(event) => {
            setChosen(event.target.value as QueueStrategy);
            setSaved(false);
          }}
          options={QUEUE_STRATEGIES.map((value) => ({ value, label: t(`priority.strategy.${value}`) }))}
        />
        <div className="flex flex-wrap items-center justify-between gap-3">
          <Button type="button" aria-label={`${t("priority.strategy.save")} ${name}`} disabled={busy || current === null} onClick={save}>
            {t("priority.strategy.save")}
          </Button>
        </div>
        {error && <ErrorAlert>{error}</ErrorAlert>}
        {saved && (
          <span className="text-fg-muted" role="status">
            {t("priority.strategy.saved")}
          </span>
        )}
        <VersionHistory
          name={name}
          load={() => client!.priority.strategyVersions(group.id)}
          revert={(versionId) => client!.priority.revertStrategy(group.id, versionId)}
          onReverted={() => {
            setSaved(false);
            client!.priority.strategy(group.id).then(
              (strategy) => {
                setCurrent(strategy);
                setChosen(strategy.strategy);
              },
              () => undefined,
            );
          }}
        />
      </div>
    </li>
  );
}
