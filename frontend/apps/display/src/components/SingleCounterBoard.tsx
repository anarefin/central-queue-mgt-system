"use client";

import { useI18n } from "@qms/i18n/react";
import { localised, localisedAll } from "../lib/displayFilters";
import { useLanguageCycle } from "../lib/languageCycle";
import type { ZoneFeed } from "../lib/useZoneFeed";

/**
 * The `single_counter` layout (ticket 30, FR-DSP-003): one large token number for a single Counter or room, chosen
 * without a code change via the display's own `layout_config.counter_id`. Falls back to the zone's first Counter if
 * none is configured yet (a display just switched to this layout before it has been given one). `feed` is the
 * shared zone feed {@link DisplayBoard} already loaded.
 */
export function SingleCounterBoard({ feed: { state, stale, now, highlightUntil } }: { feed: ZoneFeed }) {
  const { t, formatToken } = useI18n();
  const cycle = useLanguageCycle(state?.language_cycle ?? ["en"], state?.language_cycle_seconds ?? 10);

  if (!state) return null;

  const counterId = state.layout_config.counter_id;
  const row = (counterId ? state.serving.find((r) => r.counter_id === counterId) : undefined) ?? state.serving[0];

  if (!row) return <p className="text-fg-muted">{t("display.singleCounter.none")}</p>;

  const highlighted = (highlightUntil[row.counter_id] ?? 0) > now;
  const service = cycle.sideBySide ? localisedAll(row.service_names, cycle.languages) : localised(row.service_names, cycle.language);

  return (
    <div className="flex h-full flex-col items-center justify-center gap-4 p-6 text-center">
      {stale && (
        <span className="self-start rounded-full border border-warn bg-warn-subtle px-3 py-1 text-sm text-warn" role="status" aria-live="polite">
          {t("nowServing.stale")}
        </span>
      )}
      <div className={`text-[clamp(5rem,20vw,12rem)] font-bold leading-none tabular-nums ${highlighted ? "motion-safe:animate-highlight-pulse" : ""}`}>
        {formatToken(row.token_number ?? "—")}
      </div>
      <div className="text-4xl">{row.counter_label}</div>
      {service && <div className="text-2xl text-fg-muted">{service}</div>}
    </div>
  );
}
