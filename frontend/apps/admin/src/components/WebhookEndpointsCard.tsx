"use client";

import type { WebhookEndpoint } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert } from "@qms/ui";
import { useState } from "react";
import { describeError, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";
import { EntityRow } from "./EntityRow";
import { WebhookEndpointForm } from "./WebhookEndpointForm";

/**
 * Webhook endpoints (ticket 57, FR-INT-020): each subscribed to any number of §21.4 event types, with a secret the
 * API shows back only once, right after it creates the endpoint or rotates its secret. An endpoint is deactivated,
 * never deleted, so its own delivery log keeps its history. The API checks the permission (FR-CFG-103).
 */
export function WebhookEndpointsCard() {
  const { t } = useI18n();
  const { client } = useApi();
  const [adding, setAdding] = useState(false);
  const [revealedSecret, setRevealedSecret] = useState<{ description: string; secret: string } | null>(null);
  const endpoints = useList<WebhookEndpoint>(client ? () => client.webhooks.endpoints.list() : null, [client]);
  const rotate = useSubmit();

  function reveal(endpoint: WebhookEndpoint) {
    if (endpoint.secret) setRevealedSecret({ description: endpoint.description, secret: endpoint.secret });
  }

  async function rotateSecret(endpoint: WebhookEndpoint) {
    if (!client) return;
    const ok = await rotate.run(async () => {
      const updated = await client.webhooks.endpoints.rotateSecret(endpoint.id);
      reveal(updated);
    });
    if (ok) endpoints.reload();
  }

  return (
    <Card>
      <h2 className="font-semibold text-fg">{t("webhooks.endpoints.title")}</h2>
      <p className="text-fg-muted">{t("webhooks.endpoints.intro")}</p>
      {revealedSecret && (
        <div className="flex flex-col gap-4" role="status">
          <p>{t("webhooks.endpoints.secretRevealed", { description: revealedSecret.description })}</p>
          <code>{revealedSecret.secret}</code>
          <p className="text-fg-muted">{t("webhooks.endpoints.secretRevealedHint")}</p>
          <Button variant="secondary" type="button" onClick={() => setRevealedSecret(null)}>
            {t("admin.action.dismiss")}
          </Button>
        </div>
      )}
      {endpoints.error !== null && <ErrorAlert>{describeError(t, endpoints.error)}</ErrorAlert>}
      {endpoints.items?.length === 0 && <p className="text-fg-muted">{t("webhooks.endpoints.none")}</p>}
      {endpoints.items && endpoints.items.length > 0 && (
        <ul className="m-0 list-none p-0 flex flex-col divide-y divide-border [&>li]:flex [&>li]:flex-wrap [&>li]:items-center [&>li]:justify-between [&>li]:gap-2 [&>li]:py-2.5">
          {endpoints.items.map((endpoint) => (
            <EntityRow
              key={endpoint.id}
              name={endpoint.description}
              heading={endpoint.description}
              lines={[endpoint.url, t("webhooks.endpoints.subscribedTo", { count: endpoint.event_types.length })]}
              active={endpoint.active}
              confirmText={t("webhooks.endpoints.confirmDeactivate", { name: endpoint.description })}
              onDeactivate={async () => {
                await client!.webhooks.endpoints.deactivate(endpoint.id);
                endpoints.reload();
              }}
              onActivate={async () => {
                await client!.webhooks.endpoints.activate(endpoint.id);
                endpoints.reload();
              }}
              extra={
                <Button
                  variant="secondary"
                  type="button"
                  disabled={rotate.busy}
                  aria-label={`${t("webhooks.endpoints.rotateSecret")} ${endpoint.description}`}
                  onClick={() => void rotateSecret(endpoint)}
                >
                  {t("webhooks.endpoints.rotateSecret")}
                </Button>
              }
              editForm={(close) => (
                <WebhookEndpointForm
                  initial={endpoint}
                  submitLabel={t("admin.action.save")}
                  onSubmit={(input) => client!.webhooks.endpoints.update(endpoint.id, input)}
                  onDone={() => {
                    close();
                    endpoints.reload();
                  }}
                  onCancel={close}
                />
              )}
            />
          ))}
        </ul>
      )}
      {rotate.error && <ErrorAlert>{rotate.error}</ErrorAlert>}
      {adding ? (
        <WebhookEndpointForm
          submitLabel={t("webhooks.endpoints.create")}
          onSubmit={(input) => client!.webhooks.endpoints.create(input)}
          onDone={(created) => {
            setAdding(false);
            reveal(created);
            endpoints.reload();
          }}
          onCancel={() => setAdding(false)}
        />
      ) : (
        <Button variant="secondary" type="button" onClick={() => setAdding(true)}>
          {t("webhooks.endpoints.add")}
        </Button>
      )}
    </Card>
  );
}
