"use client";

import { useI18n } from "@qms/i18n/react";
import { filterNext, filterServing, localised, localisedAll } from "../lib/displayFilters";
import { useLanguageCycle } from "../lib/languageCycle";
import type { ZoneFeed } from "../lib/useZoneFeed";
import { NoticePanel } from "./NoticePanel";

const DEFAULT_SPLIT_PERCENT = 60;

/**
 * The `split_media` layout (ticket 30, FR-DSP-003): the now-serving table on one side, the notice panel on the
 * other, sized by the display's own `layout_config.split_percent` (configurable without a code change) rather than
 * a fixed ratio. `feed` is the shared zone feed {@link DisplayBoard} already loaded, so a display with several
 * possible layouts fetches and subscribes exactly once.
 */
export function SplitMediaBoard({ feed: { state, stale, now, highlightUntil } }: { feed: ZoneFeed }) {
  const { t, formatToken } = useI18n();
  const cycle = useLanguageCycle(state?.language_cycle ?? ["en"], state?.language_cycle_seconds ?? 10);

  if (!state) return null;

  const splitPercent = state.layout_config.split_percent ?? DEFAULT_SPLIT_PERCENT;
  const serving = filterServing(state);
  const next = filterNext(state);
  const name = (names: Record<string, string>) => (cycle.sideBySide ? localisedAll(names, cycle.languages) : (localised(names, cycle.language) ?? "—"));

  return (
    <div className="flex h-full flex-col gap-4 p-6">
      {stale && (
        <span className="self-start rounded-full border border-warn bg-warn-subtle px-3 py-1 text-sm text-warn" role="status" aria-live="polite">
          {t("nowServing.stale")}
        </span>
      )}
      <div className="flex min-h-0 flex-1 gap-6">
        <div
          className="flex flex-col gap-4 overflow-auto"
          data-testid="split-media-serving"
          style={{ flexGrow: 0, flexShrink: 0, flexBasis: `${splitPercent}%` }}
        >
          <table className="w-full border-collapse text-2xl">
            <thead>
              <tr>
                {state.columns.includes("token") && <th className="border-b border-border px-4 py-3 text-start">{t("nowServing.columns.token")}</th>}
                {state.columns.includes("counter") && <th className="border-b border-border px-4 py-3 text-start">{t("nowServing.columns.counter")}</th>}
                {state.columns.includes("service") && <th className="border-b border-border px-4 py-3 text-start">{t("nowServing.columns.service")}</th>}
                {state.columns.includes("staff") && <th className="border-b border-border px-4 py-3 text-start">{t("nowServing.columns.staff")}</th>}
              </tr>
            </thead>
            <tbody>
              {serving.map((row) => (
                <tr
                  key={row.counter_id}
                  className={(highlightUntil[row.counter_id] ?? 0) > now ? "bg-primary/15 motion-safe:animate-highlight-pulse" : undefined}
                >
                  {state.columns.includes("token") && (
                    <td className="border-b border-border px-4 py-3 text-[clamp(1.75rem,4vw,60px)] font-bold tabular-nums">{formatToken(row.token_number ?? "—")}</td>
                  )}
                  {state.columns.includes("counter") && <td className="border-b border-border px-4 py-3">{row.counter_label}</td>}
                  {state.columns.includes("service") && <td className="border-b border-border px-4 py-3">{name(row.service_names)}</td>}
                  {state.columns.includes("staff") && <td className="border-b border-border px-4 py-3">{row.staff_name ?? "—"}</td>}
                </tr>
              ))}
            </tbody>
          </table>
          {next.length > 0 && (
            <div className="flex flex-wrap gap-6" aria-label={t("nowServing.nextLabel")}>
              {next.map((group) => (
                <div key={group.service_id} className="flex flex-col gap-2">
                  <span className="text-fg-muted">{name(group.service_names)}</span>
                  <ol className="m-0 flex list-none gap-3 p-0 text-xl tabular-nums">
                    {group.tokens.slice(0, state.next_n).map((token) => (
                      <li key={token.token_number}>{formatToken(token.token_number)}</li>
                    ))}
                  </ol>
                </div>
              ))}
            </div>
          )}
        </div>
        <div className="min-w-0 flex-1">
          <NoticePanel notices={state.notices} language={cycle.language} />
        </div>
      </div>
    </div>
  );
}
