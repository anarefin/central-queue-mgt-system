"use client";

import { useI18n } from "@qms/i18n/react";
import { ErrorAlert } from "@qms/ui";
import { useZoneFeed } from "../lib/useZoneFeed";
import { NowServingBoard } from "./NowServingBoard";
import { SingleCounterBoard } from "./SingleCounterBoard";
import { SplitMediaBoard } from "./SplitMediaBoard";
import { SummaryBoard } from "./SummaryBoard";

/**
 * Dispatches to the display's configured layout (ticket 30, FR-DSP-003): `now_serving_table` (ticket 28, with its
 * own voice announcements, ticket 29), `split_media`, `single_counter` or `summary_board`. Loads the zone feed once
 * to learn the layout; `now_serving_table` then owns its own separate load and live subscription -- it is also
 * mounted standalone by ticket 28/29's own tests, and keeps its voice-announcement wiring, which stays specific to
 * that one layout -- while the other three layouts reuse this same feed rather than loading it twice.
 */
export function DisplayBoard({ deviceId }: { deviceId: string }) {
  const { t } = useI18n();
  const feed = useZoneFeed(deviceId);

  if (feed.error && !feed.state) return <ErrorAlert>{t("nowServing.loadError")}</ErrorAlert>;
  if (!feed.state) return <p className="qms-muted">{t("common.loading")}</p>;

  switch (feed.state.layout) {
    case "split_media":
      return <SplitMediaBoard feed={feed} />;
    case "single_counter":
      return <SingleCounterBoard feed={feed} />;
    case "summary_board":
      return <SummaryBoard feed={feed} />;
    case "now_serving_table":
    default:
      return <NowServingBoard deviceId={deviceId} />;
  }
}
