"use client";

import { ApiRequestError, type ServiceEntry, type ServiceGroup, type Site } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert } from "@qms/ui";
import { useState } from "react";
import { describeError, languageName, localisedName, useConfirmDialog, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";
import { CounterLinksCard } from "./CounterLinksCard";
import { EntityRow } from "./EntityRow";
import { OutcomeCodesCard } from "./OutcomeCodesCard";
import { ServiceForm } from "./ServiceForm";

type ServicePanel = "counters" | "outcomes";

/** The services of one service group, and below them the counters or outcome codes of the service picked here. */
export function ServicesCard({ site, group }: { site: Site; group: ServiceGroup }) {
  const { t, language } = useI18n();
  const { client } = useApi();
  const [selected, setSelected] = useState<{ id: string; panel: ServicePanel } | null>(null);
  // `group.active` is a dependency because deactivating a group also deactivates its services.
  const services = useList<ServiceEntry>(client ? () => client.catalogue.services(group.id) : null, [client, group.id, group.active]);
  const service = services.items?.find((s) => s.id === selected?.id) ?? null;
  const nameOf = (s: ServiceEntry) => localisedName(s.name_i18n, language, site.default_language);
  const groupName = localisedName(group.name_i18n, language, site.default_language);

  return (
    <>
      <Card>
        <h2 className="font-semibold text-fg">{t("catalogue.services.title", { group: groupName })}</h2>
        {services.error !== null && <ErrorAlert>{describeError(t, services.error)}</ErrorAlert>}
        {services.items?.length === 0 && <p className="text-fg-muted">{t("catalogue.services.empty")}</p>}
        {services.items && services.items.length > 0 && (
          <ul className="m-0 list-none p-0 flex flex-col divide-y divide-border [&>li]:flex [&>li]:flex-wrap [&>li]:items-center [&>li]:justify-between [&>li]:gap-2 [&>li]:py-2.5">
            {services.items.map((s) => (
              <EntityRow
                key={s.id}
                name={nameOf(s)}
                heading={nameOf(s)}
                active={s.active}
                selected={s.id === selected?.id}
                lines={[
                  t("catalogue.service.times", { prefix: s.token_prefix, expected: s.expected_minutes, sla: s.sla_wait_minutes }),
                  t("catalogue.service.rules", {
                    channels: s.channels.map((c) => t(`catalogue.channel.${c}`)).join(", "),
                    identifier: t(`catalogue.visitorIdentifier.${s.visitor_identifier}`),
                    booking: t(`catalogue.bookingMode.${s.booking_mode}`),
                  }),
                  ...(s.parallel_serving ? [t("catalogue.service.parallel", { limit: s.parallel_limit })] : []),
                  ...(s.icon ? [t("catalogue.service.icon", { icon: s.icon })] : []),
                ]}
                warnings={
                  s.missing_translations.length > 0
                    ? [t("catalogue.names.missingRow", { languages: s.missing_translations.map((l) => languageName(t, l)).join(", ") })]
                    : []
                }
                confirmText={t("catalogue.service.confirm", { name: nameOf(s) })}
                onDeactivate={async () => {
                  await client?.catalogue.deactivateService(s.id);
                  services.reload();
                }}
                onActivate={async () => {
                  await client?.catalogue.activateService(s.id);
                  services.reload();
                }}
                extra={
                  <>
                    <Button
                      variant="secondary"
                      type="button"
                      aria-label={`${t("catalogue.manageCounters")} ${nameOf(s)}`}
                      onClick={() => setSelected({ id: s.id, panel: "counters" })}
                    >
                      {t("catalogue.manageCounters")}
                    </Button>
                    <Button
                      variant="secondary"
                      type="button"
                      aria-label={`${t("catalogue.manageOutcomes")} ${nameOf(s)}`}
                      onClick={() => setSelected({ id: s.id, panel: "outcomes" })}
                    >
                      {t("catalogue.manageOutcomes")}
                    </Button>
                    <DeleteService name={nameOf(s)} onDelete={async () => {
                      await client?.catalogue.deleteService(s.id);
                      services.reload();
                    }} />
                  </>
                }
                editForm={(close) => (
                  <ServiceForm
                    site={site}
                    initial={s}
                    submitLabel={t("admin.action.save")}
                    onSubmit={async (input) => {
                      await client?.catalogue.updateService(s.id, input);
                      services.reload();
                    }}
                    onDone={close}
                    onCancel={close}
                  />
                )}
              />
            ))}
          </ul>
        )}
        <h3 className="font-semibold text-fg">{t("catalogue.services.add")}</h3>
        <ServiceForm
          key={services.items?.length ?? 0}
          site={site}
          submitLabel={t("catalogue.services.add")}
          onSubmit={async (input) => {
            await client?.catalogue.createService(group.id, input);
            services.reload();
          }}
          onDone={() => undefined}
        />
      </Card>
      {service && selected?.panel === "counters" && <CounterLinksCard key={service.id} group={group} service={service} serviceName={nameOf(service)} />}
      {service && selected?.panel === "outcomes" && <OutcomeCodesCard key={service.id} site={site} service={service} serviceName={nameOf(service)} />}
    </>
  );
}

/**
 * Deleting asks first and is refused by the API once tickets refer to the service (FR-CFG-015); the message then
 * points at deactivation, the only way out.
 */
function DeleteService({ name, onDelete }: { name: string; onDelete: () => Promise<unknown> }) {
  const { t } = useI18n();
  const [blocked, setBlocked] = useState(false);
  const { busy, error, run } = useSubmit();
  const { ask, dialog } = useConfirmDialog();

  function confirmDelete() {
    ask({
      title: `${t("catalogue.service.confirmDelete")} ${name}`,
      description: t("catalogue.service.deleteConfirm", { name }),
      confirmLabel: t("catalogue.service.confirmDelete"),
      danger: true,
      onConfirm: () => {
        setBlocked(false);
        void run(async () => {
          try {
            await onDelete();
          } catch (cause) {
            if (cause instanceof ApiRequestError && cause.code === "conflict") setBlocked(true);
            throw cause;
          }
        });
      },
    });
  }

  return (
    <>
      <Button variant="secondary" type="button" disabled={busy} aria-label={`${t("catalogue.service.delete")} ${name}`} onClick={confirmDelete}>
        {t("catalogue.service.delete")}
      </Button>
      {error && <ErrorAlert>{blocked ? t("catalogue.service.deleteBlocked") : error}</ErrorAlert>}
      {dialog}
    </>
  );
}
