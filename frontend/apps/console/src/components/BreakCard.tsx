"use client";

import type { SessionBreak } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card } from "@qms/ui";
import { useEffect, useState } from "react";
import { localisedName } from "../lib/console-support";

interface Props {
  session: { break: SessionBreak | null };
  busy: boolean;
  canEnd: boolean;
  onEnd: () => void;
}

/** Whole seconds since {@code startedAt}, moving each second, never negative (a device clock a little behind the server's). */
function useElapsedSeconds(startedAt: string | undefined): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!startedAt) return;
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [startedAt]);
  return startedAt ? Math.max(0, Math.floor((now - Date.parse(startedAt)) / 1000)) : 0;
}

/**
 * The break the agent is on (F9, FR-AGT-021, FR-AGT-022): its type, how long it has run, and, when its type has a maximum, a warning
 * once it has passed it (FR-AGT-023 raises the alert to the Team Admin on the dashboard; here the agent is only told). No new ticket is
 * assigned while it runs; F9 or the button ends it.
 */
export function BreakCard({ session, busy, canEnd, onEnd }: Props) {
  const { t, language, formatNumber } = useI18n();
  const onBreak = session.break;
  const elapsed = useElapsedSeconds(onBreak?.started_at);
  if (!onBreak) return null;
  const max = onBreak.type.max_minutes;
  const over = max !== null && elapsed > max * 60;
  return (
    <Card>
      <h3 className="qms-heading">{t("console.break.on", { type: localisedName(onBreak.type.name_i18n, language) })}</h3>
      <p role="timer" aria-live="off">
        {t("console.break.elapsed", { minutes: formatNumber(Math.floor(elapsed / 60)), seconds: formatNumber(elapsed % 60) })}
        {max !== null && <span className="qms-muted"> · {t("console.break.limit", { minutes: formatNumber(max) })}</span>}
      </p>
      <p className="qms-muted">{t("console.break.paused")}</p>
      {over && (
        <p className="qms-warning" role="status">
          {t("console.break.over", { minutes: formatNumber(max) })}
        </p>
      )}
      <div>
        <Button type="button" disabled={!canEnd || busy} onClick={onEnd} aria-keyshortcuts="F9">
          {t("console.action.endBreak")} <kbd>F9</kbd>
        </Button>
      </div>
    </Card>
  );
}
