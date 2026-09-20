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
export function SplitMediaBoard({ feed: { state, stale } }: { feed: ZoneFeed }) {
  const { t } = useI18n();
  const cycle = useLanguageCycle(state?.language_cycle ?? ["en"], state?.language_cycle_seconds ?? 10);

  if (!state) return null;

  const splitPercent = state.layout_config.split_percent ?? DEFAULT_SPLIT_PERCENT;
  const serving = filterServing(state);
  const next = filterNext(state);
  const name = (names: Record<string, string>) => (cycle.sideBySide ? localisedAll(names, cycle.languages) : (localised(names, cycle.language) ?? "—"));

  return (
    <div className="qms-split-media">
      {stale && (
        <span className="qms-now-serving-stale" role="status" aria-live="polite">
          {t("nowServing.stale")}
        </span>
      )}
      <div className="qms-split-media-panes" style={{ ["--qms-split-percent" as string]: String(splitPercent) }}>
        <div className="qms-split-media-serving">
          <table className="qms-now-serving-table">
            <thead>
              <tr>
                {state.columns.includes("token") && <th>{t("nowServing.columns.token")}</th>}
                {state.columns.includes("counter") && <th>{t("nowServing.columns.counter")}</th>}
                {state.columns.includes("service") && <th>{t("nowServing.columns.service")}</th>}
                {state.columns.includes("staff") && <th>{t("nowServing.columns.staff")}</th>}
              </tr>
            </thead>
            <tbody>
              {serving.map((row) => (
                <tr key={row.counter_id}>
                  {state.columns.includes("token") && <td className="qms-now-serving-token">{row.token_number ?? "—"}</td>}
                  {state.columns.includes("counter") && <td>{row.counter_label}</td>}
                  {state.columns.includes("service") && <td>{name(row.service_names)}</td>}
                  {state.columns.includes("staff") && <td>{row.staff_name ?? "—"}</td>}
                </tr>
              ))}
            </tbody>
          </table>
          {next.length > 0 && (
            <div className="qms-now-serving-next" aria-label={t("nowServing.nextLabel")}>
              {next.map((group) => (
                <div key={group.service_id} className="qms-now-serving-next-group">
                  <span className="qms-muted">{name(group.service_names)}</span>
                  <ol>
                    {group.tokens.slice(0, state.next_n).map((token) => (
                      <li key={token.token_number}>{token.token_number}</li>
                    ))}
                  </ol>
                </div>
              ))}
            </div>
          )}
        </div>
        <div className="qms-split-media-notice">
          <NoticePanel notices={state.notices} language={cycle.language} />
        </div>
      </div>
    </div>
  );
}
