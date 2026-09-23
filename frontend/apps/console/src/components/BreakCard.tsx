"use client";

import type { SessionBreak } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Badge, Button, Card } from "@qms/ui";
import { localisedName, useElapsedSeconds } from "../lib/console-support";

interface Props {
  session: { break: SessionBreak | null };
  busy: boolean;
  canEnd: boolean;
  onEnd: () => void;
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
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="font-semibold text-fg">{t("console.break.on", { type: localisedName(onBreak.type.name_i18n, language) })}</h3>
        <Badge variant={over ? "warn" : "neutral"}>{t("console.session.state.on_break")}</Badge>
      </div>
      <p role="timer" aria-live="off" className="text-lg font-semibold tabular-nums text-fg">
        {t("console.break.elapsed", { minutes: formatNumber(Math.floor(elapsed / 60)), seconds: formatNumber(elapsed % 60) })}
        {max !== null && <span className="text-sm font-normal text-fg-muted"> · {t("console.break.limit", { minutes: formatNumber(max) })}</span>}
      </p>
      <p className="text-fg-muted">{t("console.break.paused")}</p>
      {over && (
        <p className="text-warn" role="status">
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
