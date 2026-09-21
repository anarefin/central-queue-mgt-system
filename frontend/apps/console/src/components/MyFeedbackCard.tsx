"use client";

import type { MyFeedback } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Card } from "@qms/ui";
import { useEffect, useRef, useState } from "react";
import { useApi } from "../lib/runtime";

/**
 * The agent's own feedback (SRS §13.4, FR-MOB-033): every rating a visitor left on one of their tickets, and any
 * comment a Team Admin has approved for them to see — an unapproved comment simply is not here yet, the same "quiet
 * gap, not an error" shape {@link DayCard} already uses for its own read.
 */
export function MyFeedbackCard() {
  const { t, formatNumber } = useI18n();
  const { client } = useApi();
  const [items, setItems] = useState<MyFeedback[] | null>(null);
  const [failed, setFailed] = useState(false);
  const asked = useRef(0);

  useEffect(() => {
    if (!client) return;
    const mine = ++asked.current;
    client.feedback.mine().then(
      (result) => {
        if (mine !== asked.current) return;
        setItems(result.items);
        setFailed(false);
      },
      () => {
        if (mine === asked.current) setFailed(true);
      },
    );
  }, [client]);

  return (
    <Card>
      <h3 className="qms-label">{t("console.feedback.title")}</h3>
      {items && items.length === 0 && <p className="qms-muted">{t("console.feedback.none")}</p>}
      {items && items.length > 0 && (
        <ul className="qms-list" data-testid="feedback">
          {items.map((entry) => (
            <li key={entry.ticket_id}>
              <p>{t("console.feedback.entry", { token: entry.token_number, rating: formatNumber(entry.rating) })}</p>
              {entry.comment && <p className="qms-muted">{entry.comment}</p>}
            </li>
          ))}
        </ul>
      )}
      {!items && failed && <p className="qms-muted">{t("console.feedback.unavailable")}</p>}
    </Card>
  );
}
