"use client";

import { useI18n } from "@qms/i18n/react";
import { localised, localisedAll } from "../lib/displayFilters";
import { useLanguageCycle } from "../lib/languageCycle";
import type { ZoneFeed } from "../lib/useZoneFeed";

/**
 * The `summary_board` layout (ticket 30, FR-DSP-003): per-Service waiting counts and estimated waits for an
 * entrance lobby (SRS §10.5), from the same rounded range a kiosk shows a visitor -- never an exact promise
 * (FR-QUE-042, FR-ISS-005). `feed` is the shared zone feed {@link DisplayBoard} already loaded.
 */
export function SummaryBoard({ feed: { state, stale } }: { feed: ZoneFeed }) {
  const { t, formatNumber } = useI18n();
  const cycle = useLanguageCycle(state?.language_cycle ?? ["en"], state?.language_cycle_seconds ?? 10);

  if (!state) return null;

  const name = (names: Record<string, string>) => (cycle.sideBySide ? localisedAll(names, cycle.languages) : (localised(names, cycle.language) ?? "—"));

  return (
    <div className="flex flex-col gap-6 p-6">
      {stale && (
        <span className="self-start rounded-full border border-warn bg-warn-subtle px-3 py-1 text-sm text-warn" role="status" aria-live="polite">
          {t("nowServing.stale")}
        </span>
      )}
      <table className="w-full border-collapse text-2xl">
        <thead>
          <tr>
            <th className="border-b border-border px-4 py-3 text-start">{t("display.summary.service")}</th>
            <th className="border-b border-border px-4 py-3 text-start">{t("display.summary.waiting")}</th>
            <th className="border-b border-border px-4 py-3 text-start">{t("display.summary.estimate")}</th>
          </tr>
        </thead>
        <tbody>
          {state.summary.map((row) => (
            <tr key={row.service_id}>
              <td className="border-b border-border px-4 py-3">{name(row.service_names)}</td>
              <td className="border-b border-border px-4 py-3 font-bold tabular-nums">{formatNumber(row.waiting_count)}</td>
              <td className="border-b border-border px-4 py-3">
                {t("display.summary.estimateRange", { low: row.estimate_low_minutes, high: row.estimate_high_minutes })}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {state.summary.length === 0 && <p className="text-fg-muted">{t("display.summary.empty")}</p>}
    </div>
  );
}
