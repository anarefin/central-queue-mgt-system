"use client";

import { ApiRequestError } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField } from "@qms/ui";
import { useState } from "react";
import { useApi } from "../lib/runtime";

const RATINGS = ["1", "2", "3", "4", "5"];

/**
 * The visitor's own optional feedback, once, after their ticket is completed (FR-MOB-033): a 1-5 rating and an
 * optional comment. Declining ("not now") is a local choice only — nothing is sent, since the whole thing is
 * optional by requirement. A resubmit (e.g. after a page reload) is answered the same as success, since the visitor
 * has no way to tell "already recorded" apart from "just recorded" and neither needs a different reaction from them.
 */
export function FeedbackForm({ ticketId, credential }: { ticketId: string; credential: string }) {
  const { t } = useI18n();
  const { client } = useApi();
  const [rating, setRating] = useState("");
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [skipped, setSkipped] = useState(false);

  async function submit() {
    if (!client || rating === "") return;
    setBusy(true);
    setError(null);
    try {
      await client.tickets.submitFeedback(ticketId, credential, {
        rating: Number(rating),
        comment: comment.trim() === "" ? undefined : comment.trim(),
      });
      setDone(true);
    } catch (cause) {
      const reason = cause instanceof ApiRequestError ? cause.body?.details?.reason : undefined;
      if (reason === "feedback_already_submitted") {
        setDone(true);
        return;
      }
      const code = cause instanceof ApiRequestError ? cause.code : "network_error";
      setError(t(`errors.${code}`));
    } finally {
      setBusy(false);
    }
  }

  if (skipped) return null;

  if (done) {
    return (
      <Card>
        <p>{t("visitor.feedback.thanks")}</p>
      </Card>
    );
  }

  return (
    <Card>
      <h2 className="qms-label">{t("visitor.feedback.title")}</h2>
      <p className="qms-muted">{t("visitor.feedback.intro")}</p>
      {error && <ErrorAlert>{error}</ErrorAlert>}
      <SelectField
        id="feedback-rating"
        label={t("visitor.feedback.ratingLabel")}
        value={rating}
        onChange={(event) => setRating(event.target.value)}
        options={[{ value: "", label: t("visitor.feedback.ratingPlaceholder") }, ...RATINGS.map((value) => ({ value, label: value }))]}
      />
      <div>
        <label className="qms-label" htmlFor="feedback-comment">
          {t("visitor.feedback.commentLabel")}
        </label>
        <textarea className="qms-input" id="feedback-comment" value={comment} onChange={(event) => setComment(event.target.value)} maxLength={2000} />
      </div>
      <div className="qms-row">
        <Button type="button" onClick={() => void submit()} disabled={busy || rating === ""}>
          {busy ? t("visitor.feedback.submitting") : t("visitor.feedback.submit")}
        </Button>
        <Button type="button" variant="secondary" onClick={() => setSkipped(true)} disabled={busy}>
          {t("visitor.feedback.skip")}
        </Button>
      </div>
    </Card>
  );
}
