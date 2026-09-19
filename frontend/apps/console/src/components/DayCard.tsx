"use client";

import type { AgentDay } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Card } from "@qms/ui";
import { useEffect, useRef, useState } from "react";
import { useApi } from "../lib/runtime";

interface Props {
  /** Changes whenever what the agent has done may have changed their day (a ticket moved, a break began or ended): the counts are read again. */
  refreshKey: string;
}

/**
 * The agent's own day so far (SRS §11.5, FR-AGT-040): tickets served, tickets waiting for the Services of their session, the average
 * service time and the break time. The API answers with the caller's figures alone, so there is nothing here to compare with a colleague, and
 * nothing that ranks. A day that cannot be read is said so quietly: it must not get in the way of serving.
 */
export function DayCard({ refreshKey }: Props) {
  const { t, formatNumber } = useI18n();
  const { client } = useApi();
  const [day, setDay] = useState<AgentDay | null>(null);
  const [failed, setFailed] = useState(false);
  const asked = useRef(0);

  useEffect(() => {
    if (!client) return;
    const mine = ++asked.current;
    client.sessions
      .day()
      .then((next) => {
        if (mine !== asked.current) return;
        setDay(next);
        setFailed(false);
      })
      .catch(() => {
        if (mine === asked.current) setFailed(true);
      });
  }, [client, refreshKey]);

  const duration = (seconds: number) =>
    t("console.duration", { minutes: formatNumber(Math.floor(seconds / 60)), seconds: formatNumber(seconds % 60) });

  return (
    <Card>
      <h3 className="qms-label">{t("console.day.title")}</h3>
      {day && (
        <ul className="qms-list" data-testid="day">
          <li>{t("console.day.served", { count: formatNumber(day.served) })}</li>
          <li>{t("console.day.inQueue", { count: formatNumber(day.in_queue) })}</li>
          <li>{day.average_service_seconds === null ? t("console.day.averageNone") : t("console.day.average", { duration: duration(day.average_service_seconds) })}</li>
          <li>{t("console.day.breaks", { duration: duration(day.break_seconds) })}</li>
        </ul>
      )}
      {!day && failed && <p className="qms-muted">{t("console.day.unavailable")}</p>}
    </Card>
  );
}
