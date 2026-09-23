"use client";

import type { CounterLink, CounterOption, ServiceEntry, ServiceGroup } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField, TextField } from "@qms/ui";
import { useId, useState, type FormEvent } from "react";
import { describeError, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

/** The counters that serve one service, each with a preference weight: 1 is primary, higher is a fallback (FR-CFG-011). */
export function CounterLinksCard({ group, service, serviceName }: { group: ServiceGroup; service: ServiceEntry; serviceName: string }) {
  const { t } = useI18n();
  const { client } = useApi();
  const id = useId();
  const links = useList<CounterLink>(client ? () => client.catalogue.links(service.id) : null, [client, service.id]);
  const options = useList<CounterOption>(client ? () => client.catalogue.counterOptions(group.id) : null, [client, group.id]);
  const [counterId, setCounterId] = useState("");
  const [weight, setWeight] = useState("1");
  const { busy, error, run } = useSubmit();
  const rowError = useSubmit();

  const linked = new Set(links.items?.map((l) => l.counter_id));
  const free = options.items?.filter((o) => !linked.has(o.id)) ?? [];

  async function add(event: FormEvent) {
    event.preventDefault();
    if (await run(() => client!.catalogue.link(service.id, counterId, Number(weight)))) {
      setCounterId("");
      setWeight("1");
      links.reload();
    }
  }

  return (
    <Card>
      <h2 className="font-semibold text-fg">{t("catalogue.links.title", { service: serviceName })}</h2>
      {links.error !== null && <ErrorAlert>{describeError(t, links.error)}</ErrorAlert>}
      {links.items?.length === 0 && <p className="text-fg-muted">{t("catalogue.links.empty")}</p>}
      {links.items && links.items.length > 0 && (
        <ul className="m-0 list-none p-0 flex flex-col divide-y divide-border [&>li]:flex [&>li]:flex-wrap [&>li]:items-center [&>li]:justify-between [&>li]:gap-2 [&>li]:py-2.5">
          {links.items.map((link) => (
            <li key={link.counter_id}>
              <div className="flex flex-wrap items-center justify-between gap-3 flex-1 min-w-0">
                <span>{t("catalogue.link.line", { counter: link.counter_label, weight: link.preference_weight })}</span>
                <Button
                  variant="secondary"
                  type="button"
                  aria-label={`${t("catalogue.link.remove")} ${link.counter_label}`}
                  disabled={rowError.busy}
                  onClick={async () => {
                    if (await rowError.run(() => client!.catalogue.unlink(service.id, link.counter_id))) links.reload();
                  }}
                >
                  {t("catalogue.link.remove")}
                </Button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {rowError.error && <ErrorAlert>{rowError.error}</ErrorAlert>}
      <h3 className="font-semibold text-fg">{t("catalogue.links.add")}</h3>
      <form className="flex flex-col gap-4" onSubmit={add}>
        <SelectField
          id={`${id}-counter`}
          label={t("catalogue.fields.counter_id")}
          value={counterId}
          onChange={(event) => setCounterId(event.target.value)}
          options={[
            { value: "", label: t("catalogue.links.choose") },
            ...free.map((o) => ({ value: o.id, label: t("catalogue.link.counterOption", { zone: o.zone_name, counter: o.label }) })),
          ]}
        />
        <TextField
          id={`${id}-weight`}
          type="number"
          label={t("catalogue.fields.preference_weight")}
          value={weight}
          onChange={(event) => setWeight(event.target.value)}
        />
        <p className="text-fg-muted">{t("catalogue.links.hint")}</p>
        {error && <ErrorAlert>{error}</ErrorAlert>}
        <Button type="submit" disabled={busy || counterId === ""}>
          {t("catalogue.links.add")}
        </Button>
      </form>
    </Card>
  );
}
