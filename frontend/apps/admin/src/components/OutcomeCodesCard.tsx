"use client";

import type { OutcomeCode, ServiceEntry, Site } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Card, ErrorAlert } from "@qms/ui";
import { describeError, languageName, localisedName, useList } from "../lib/admin-support";
import { useApi } from "../lib/runtime";
import { EntityRow } from "./EntityRow";
import { OutcomeCodeForm } from "./OutcomeCodeForm";

/** The outcome codes agents choose from when a ticket of the service completes (FR-AGT-032, FR-AGT-033). */
export function OutcomeCodesCard({ site, service, serviceName }: { site: Site; service: ServiceEntry; serviceName: string }) {
  const { t, language } = useI18n();
  const { client } = useApi();
  const outcomes = useList<OutcomeCode>(client ? () => client.catalogue.outcomes(service.id) : null, [client, service.id, service.active]);
  const labelOf = (o: OutcomeCode) => localisedName(o.label_i18n, language, site.default_language);

  return (
    <Card>
      <h2 className="qms-heading">{t("catalogue.outcomes.title", { service: serviceName })}</h2>
      {outcomes.error !== null && <ErrorAlert>{describeError(t, outcomes.error)}</ErrorAlert>}
      {outcomes.items?.length === 0 && <p className="qms-muted">{t("catalogue.outcomes.empty")}</p>}
      {outcomes.items && outcomes.items.length > 0 && (
        <ul className="qms-list">
          {outcomes.items.map((o) => (
            <EntityRow
              key={o.id}
              name={labelOf(o)}
              heading={labelOf(o)}
              active={o.active}
              lines={[t("catalogue.outcome.line", { code: o.code, order: o.display_order })]}
              warnings={
                o.missing_translations.length > 0
                  ? [t("catalogue.names.missingRow", { languages: o.missing_translations.map((l) => languageName(t, l)).join(", ") })]
                  : []
              }
              confirmText={t("catalogue.outcome.confirm", { name: labelOf(o) })}
              onDeactivate={async () => {
                await client?.catalogue.deactivateOutcome(o.id);
                outcomes.reload();
              }}
              onActivate={async () => {
                await client?.catalogue.activateOutcome(o.id);
                outcomes.reload();
              }}
              editForm={(close) => (
                <OutcomeCodeForm
                  site={site}
                  initial={o}
                  submitLabel={t("admin.action.save")}
                  onSubmit={async (input) => {
                    await client?.catalogue.updateOutcome(o.id, { label_i18n: input.label_i18n, display_order: input.display_order });
                    outcomes.reload();
                  }}
                  onDone={close}
                  onCancel={close}
                />
              )}
            />
          ))}
        </ul>
      )}
      <h3 className="qms-heading">{t("catalogue.outcomes.add")}</h3>
      <OutcomeCodeForm
        key={outcomes.items?.length ?? 0}
        site={site}
        submitLabel={t("catalogue.outcomes.add")}
        onSubmit={async (input) => {
          await client?.catalogue.createOutcome(service.id, input);
          outcomes.reload();
        }}
        onDone={() => undefined}
      />
    </Card>
  );
}
