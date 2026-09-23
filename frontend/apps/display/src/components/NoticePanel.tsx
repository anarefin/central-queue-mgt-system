"use client";

import type { DisplayNotice } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { useEffect, useState } from "react";
import { localised } from "../lib/displayFilters";

/** How long each notice-board item shows before the playlist advances (FR-DSP-006's "scheduled playlist"); the SRS
 * gives no number for the per-item on-screen duration (only the per-item start/end dates), so this is this build's
 * own reasonable choice. */
const ITEM_SECONDS = 12;

/**
 * The notice panel of the `split_media` layout (FR-DSP-006): images, video or rich text, in a playlist of whatever
 * the zone has scheduled active right now (the backend already filtered `notices` to the zone, active, and inside
 * its own start/end date window). Cycles through the playlist on a timer; a single item just stays on screen.
 */
export function NoticePanel({ notices, language }: { notices: DisplayNotice[]; language: string }) {
  const { t } = useI18n();
  const [index, setIndex] = useState(0);

  useEffect(() => {
    setIndex(0);
  }, [notices.length]);

  useEffect(() => {
    if (notices.length <= 1) return;
    const interval = setInterval(() => setIndex((i) => (i + 1) % notices.length), ITEM_SECONDS * 1000);
    return () => clearInterval(interval);
  }, [notices.length]);

  if (notices.length === 0) {
    return (
      <div className="flex h-full items-center justify-center overflow-hidden rounded-lg bg-surface-muted">
        <p className="text-fg-muted">{t("display.notices.empty")}</p>
      </div>
    );
  }

  const notice = notices[index % notices.length]!;
  const content = localised(notice.content_i18n, language) ?? "";

  return (
    <div className="flex h-full items-center justify-center overflow-hidden rounded-lg bg-surface-muted" aria-label={t("display.notices.label")}>
      {notice.type === "image" && content && <img className="max-h-full max-w-full object-contain" src={content} alt="" />}
      {notice.type === "video" && content && (
        // eslint-disable-next-line jsx-a11y/media-has-caption -- notice-board media is decorative signage, not narrated content
        <video className="max-h-full max-w-full object-contain" src={content} autoPlay muted loop playsInline />
      )}
      {notice.type === "rich_text" && <p className="whitespace-pre-wrap p-8 text-center text-2xl">{content}</p>}
    </div>
  );
}
