"use client";

import type { PendingFeedbackComment } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert } from "@qms/ui";
import { useState } from "react";
import { describeError, useList } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

/**
 * A Team Admin's own review queue for post-service feedback comments (ticket 45, FR-MOB-033): a comment is never
 * shown to the agent it is about, individually, until approved here. The rating itself is never gated (it already
 * feeds the aggregate Feedback report) — only the free-text comment needs a human decision before an individual
 * agent sees it.
 */
export function FeedbackAdmin() {
  const { t, formatNumber } = useI18n();
  const { client } = useApi();
  const [approvingId, setApprovingId] = useState<string | null>(null);
  const pending = useList<PendingFeedbackComment>(client ? () => client.feedback.pendingComments() : null, [client]);

  async function approve(id: string) {
    if (!client) return;
    setApprovingId(id);
    try {
      await client.feedback.approveComment(id);
      pending.reload();
    } finally {
      setApprovingId(null);
    }
  }

  const items = pending.items;

  return (
    <div className="flex flex-col gap-4">
      <p className="text-fg-muted">{t("feedback.admin.intro")}</p>
      <Card>
        <h2 className="font-semibold text-fg">{t("feedback.admin.title")}</h2>
        {pending.error !== null && <ErrorAlert>{describeError(t, pending.error)}</ErrorAlert>}
        {items?.length === 0 && <p className="text-fg-muted">{t("feedback.admin.none")}</p>}
        {items && items.length > 0 && (
          <ul className="m-0 list-none p-0 flex flex-col divide-y divide-border [&>li]:flex [&>li]:flex-wrap [&>li]:items-center [&>li]:justify-between [&>li]:gap-2 [&>li]:py-2.5">
            {items.map((item) => (
              <li key={item.id} className="flex flex-col gap-4">
                <p>
                  <strong>{item.token_number}</strong> — {t("feedback.admin.rating", { rating: formatNumber(item.rating) })}
                </p>
                <p>{item.comment}</p>
                <div>
                  <Button variant="secondary" type="button" onClick={() => void approve(item.id)} disabled={approvingId === item.id}>
                    {t("feedback.admin.approve")}
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
