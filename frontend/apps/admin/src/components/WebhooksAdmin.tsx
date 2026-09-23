"use client";

import { useI18n } from "@qms/i18n/react";
import { WebhookDeliveryLogCard } from "./WebhookDeliveryLogCard";
import { WebhookEndpointsCard } from "./WebhookEndpointsCard";

/** Outbound webhooks (ticket 57, SRS §22.3, FR-INT-020..022): endpoints subscribed to any §21.4 event type, and
 * their delivery log with replay. */
export function WebhooksAdmin() {
  const { t } = useI18n();
  return (
    <div className="flex flex-col gap-4">
      <p className="text-fg-muted">{t("webhooks.intro")}</p>
      <WebhookEndpointsCard />
      <WebhookDeliveryLogCard />
    </div>
  );
}
