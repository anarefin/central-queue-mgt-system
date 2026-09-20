"use client";

import type { Notice, NoticeInput, NoticeType } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert, SelectField, TextField } from "@qms/ui";
import { useId, useState, type FormEvent } from "react";
import { useSubmit } from "../lib/admin-support";
import { nameValues, TranslatedNameFields } from "./TranslatedNameFields";

const NOTICE_TYPES: NoticeType[] = ["image", "video", "rich_text"];

interface NoticeFormProps {
  zoneId: string;
  languages: string[];
  defaultLanguage: string;
  initial?: Notice;
  submitLabel: string;
  onSubmit: (input: NoticeInput) => Promise<unknown>;
  onDone: () => void;
  onCancel?: () => void;
}

/** Converts an ISO instant to/from the value a `datetime-local` input wants (local time, no seconds/zone). */
function toLocalInput(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * One notice-board item (ticket 30, FR-DSP-006): its type, one piece of content per enabled language (so an image
 * containing text can ship one asset per language, FR-I18N-032), and the playlist window it is scheduled for.
 */
export function NoticeForm({ zoneId, languages, defaultLanguage, initial, submitLabel, onSubmit, onDone, onCancel }: NoticeFormProps) {
  const { t } = useI18n();
  const id = useId();
  const [type, setType] = useState<NoticeType>(initial?.type ?? "image");
  const [content, setContent] = useState(nameValues(languages, initial?.content_i18n));
  const [startsAt, setStartsAt] = useState(initial ? toLocalInput(initial.starts_at) : "");
  const [endsAt, setEndsAt] = useState(initial ? toLocalInput(initial.ends_at) : "");
  const [sortOrder, setSortOrder] = useState(String(initial?.sort_order ?? 0));
  const { busy, error, run } = useSubmit();

  async function submit(event: FormEvent) {
    event.preventDefault();
    const input: NoticeInput = {
      zone_id: zoneId,
      type,
      content_i18n: content,
      starts_at: new Date(startsAt).toISOString(),
      ends_at: new Date(endsAt).toISOString(),
      sort_order: Number(sortOrder),
    };
    if (await run(() => onSubmit(input))) onDone();
  }

  return (
    <form className="qms-stack" onSubmit={submit}>
      <SelectField
        id={`${id}-type`}
        label={t("noticeBoard.fields.type")}
        value={type}
        onChange={(e) => setType(e.target.value as NoticeType)}
        options={NOTICE_TYPES.map((value) => ({ value, label: t(`noticeBoard.type.${value}`) }))}
      />
      <TranslatedNameFields
        id={`${id}-content`}
        label={t("noticeBoard.fields.content")}
        languages={languages}
        defaultLanguage={defaultLanguage}
        value={content}
        onChange={setContent}
      />
      <TextField id={`${id}-starts`} type="datetime-local" label={t("noticeBoard.fields.startsAt")} value={startsAt} onChange={(e) => setStartsAt(e.target.value)} />
      <TextField id={`${id}-ends`} type="datetime-local" label={t("noticeBoard.fields.endsAt")} value={endsAt} onChange={(e) => setEndsAt(e.target.value)} />
      <TextField id={`${id}-sort`} type="number" min={0} label={t("noticeBoard.fields.sortOrder")} value={sortOrder} onChange={(e) => setSortOrder(e.target.value)} />
      {error && <ErrorAlert>{error}</ErrorAlert>}
      <div className="qms-row">
        <Button type="submit" disabled={busy}>
          {busy ? t("admin.action.saving") : submitLabel}
        </Button>
        {onCancel && (
          <Button variant="secondary" type="button" onClick={onCancel}>
            {t("admin.action.cancel")}
          </Button>
        )}
      </div>
    </form>
  );
}
