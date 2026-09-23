"use client";

import { ApiRequestError, type PriorityClass, type QueuedTicket } from "@qms/api-client";
import { formatTokenNumber } from "@qms/i18n";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField, TextField } from "@qms/ui";
import { useState, type FormEvent } from "react";
import { describeError } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

/** Reasons the API gives a refused change or cancel that this panel has a sentence for. */
const REFUSALS = new Set(["ticket_not_waiting", "same_priority_class", "ticket_not_active", "version_mismatch"]);

interface Props {
  mode: "priority" | "cancel";
  entry: QueuedTicket;
  /** Every Priority class; the panel offers the active ones the ticket does not already have. */
  classes: PriorityClass[];
  nameOf: (names: Record<string, string>) => string;
  onClose: () => void;
  /** The action went through; `message` says what happened, for the screen to show while it reads the queue again. */
  onDone: (message: string) => void;
}

/**
 * One action on a queued ticket: change its Priority class, with a mandatory reason (FR-QUE-012, UAT U9), or cancel it, with an
 * optional one (SRS §19.1). The screen only asks; the API checks the permission, the site and the ticket's state (FR-CFG-103).
 */
export function TicketActionPanel({ mode, entry, classes, nameOf, onClose, onDone }: Props) {
  const { t } = useI18n();
  const { client } = useApi();
  const token = formatTokenNumber(entry.token_number);
  const options = classes.filter((c) => c.active && c.id !== entry.priority_class?.id);
  const [chosen, setChosen] = useState<string>(options[0]?.id ?? "");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function refusalText(cause: unknown): string {
    const why = cause instanceof ApiRequestError ? cause.body?.details?.reason : undefined;
    if (cause instanceof ApiRequestError && cause.code === "conflict" && typeof why === "string" && REFUSALS.has(why)) {
      return t(`reception.action.refused.${why}`);
    }
    return describeError(t, cause);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!client) return;
    const text = reason.trim();
    if (mode === "priority" && text === "") {
      setError(t("reception.action.reasonRequired"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (mode === "priority") {
        const change = await client.tickets.setPriority(entry.id, { priority_class_id: chosen, reason: text });
        const name = nameOf(classes.find((c) => c.id === change.priority_class_id)?.name_i18n ?? {});
        onDone(t("reception.priorityChange.saved", { token, name, position: change.position ?? "" }));
      } else {
        await client.tickets.cancel(entry.id, text === "" ? undefined : text);
        onDone(t("reception.cancelTicket.done", { token }));
      }
    } catch (cause) {
      setError(refusalText(cause));
      setBusy(false);
    }
  }

  const noClass = mode === "priority" && options.length === 0;
  return (
    <Card>
      <form className="flex flex-col gap-4" onSubmit={submit} aria-label={mode === "priority" ? t("reception.priorityChange.title", { token }) : t("reception.cancelTicket.title", { token })}>
        <h3 className="font-semibold text-fg">{mode === "priority" ? t("reception.priorityChange.title", { token }) : t("reception.cancelTicket.title", { token })}</h3>
        {noClass && <p className="text-fg-muted">{t("reception.priorityChange.none")}</p>}
        {mode === "priority" && !noClass && (
          <SelectField
            id={`ticket-class-${entry.id}`}
            label={t("reception.priorityChange.class")}
            value={chosen}
            onChange={(event) => setChosen(event.target.value)}
            options={options.map((c) => ({
              value: c.id,
              label: c.is_default ? nameOf(c.name_i18n) : t("reception.priority.option", { name: nameOf(c.name_i18n), minutes: c.headstart_minutes }),
            }))}
          />
        )}
        <TextField
          id={`ticket-reason-${entry.id}`}
          label={t(mode === "priority" ? "reception.priorityChange.reason" : "reception.cancelTicket.reason")}
          value={reason}
          maxLength={1000}
          onChange={(event) => setReason(event.target.value)}
        />
        {error !== null && <ErrorAlert>{error}</ErrorAlert>}
        <div className="flex flex-wrap items-center justify-between gap-3">
          <Button type="submit" disabled={busy || noClass}>
            {mode === "priority" ? t(busy ? "reception.priorityChange.saving" : "reception.priorityChange.save") : t(busy ? "reception.cancelTicket.cancelling" : "reception.cancelTicket.confirm")}
          </Button>
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("reception.action.keep")}
          </Button>
        </div>
      </form>
    </Card>
  );
}
