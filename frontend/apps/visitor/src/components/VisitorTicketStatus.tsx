"use client";

import { ApiRequestError } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ConfirmDialog, ErrorAlert, Page, StatusBadge } from "@qms/ui";
import { useEffect, useState } from "react";
import { useApi } from "../lib/runtime";
import { useTicketStream, type TicketStreamDeps } from "../lib/ticketStream";
import { FeedbackForm } from "./FeedbackForm";
import { PushOptIn } from "./PushOptIn";
import { RemoteCheckIn } from "./RemoteCheckIn";

/** A visitor may cancel any time before being called (FR-MOB-030); once called, serving or held, the button is gone. */
const CANCELLABLE_STATES = new Set(["remote", "waiting", "paused"]);

/**
 * The site's own QR opens this same page with `?checkin=qr` (ticket 43, FR-MOB-021, FR-MOB-024): reaching here by
 * scanning it at the site is itself the proof of presence, so {@link RemoteCheckIn} confirms it directly rather than
 * asking for the device's location.
 */
function useOpenedByQr(): boolean {
  const [qr, setQr] = useState(false);
  useEffect(() => {
    setQr(new URLSearchParams(window.location.search).get("checkin") === "qr");
  }, []);
  return qr;
}

export function VisitorTicketStatus({
  ticketId,
  credential,
  streamDeps,
}: {
  ticketId: string;
  credential: string;
  /** Overrides the realtime hook's socket/timers for tests; production code never passes this. */
  streamDeps?: TicketStreamDeps;
}) {
  const { t, formatToken, formatNumber, formatTime } = useI18n();
  const { client, apiOrigin, error: configError } = useApi();
  const stream = useTicketStream(client, apiOrigin, ticketId, credential, streamDeps);
  const openedByQr = useOpenedByQr();
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);
  const [cancelled, setCancelled] = useState(false);
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [optedOut, setOptedOut] = useState(false);
  const [optOutBusy, setOptOutBusy] = useState(false);
  const [optOutError, setOptOutError] = useState<string | null>(null);

  async function cancel() {
    setConfirmingCancel(false);
    if (!client) return;
    setCancelling(true);
    setCancelError(null);
    try {
      await client.tickets.visitorCancel(ticketId, credential);
      setCancelled(true);
    } catch (cause) {
      const reason = cause instanceof ApiRequestError ? cause.body?.details?.reason : undefined;
      const code = cause instanceof ApiRequestError ? cause.code : "network_error";
      setCancelError(reason === "ticket_already_called" ? t("visitor.cancel.refused.ticket_already_called") : t(`errors.${code}`));
    } finally {
      setCancelling(false);
    }
  }

  async function toggleOptOut() {
    if (!client) return;
    setOptOutBusy(true);
    setOptOutError(null);
    try {
      const next = !optedOut;
      await client.tickets.notificationOptOut(ticketId, credential, next);
      setOptedOut(next);
    } catch (cause) {
      const code = cause instanceof ApiRequestError ? cause.code : "network_error";
      setOptOutError(t(`errors.${code}`));
    } finally {
      setOptOutBusy(false);
    }
  }

  if (configError) {
    return (
      <Page>
        <Card>
          <ErrorAlert>{t("visitor.error.generic")}</ErrorAlert>
        </Card>
      </Page>
    );
  }

  // A wrong or unknown ticket id and secret never becomes right (FR-SEC-033); this is the page's own message, not the
  // staff-facing "please sign in" (errors.unauthenticated) which makes no sense to an anonymous visitor.
  if (stream.errorCode === "unauthenticated") {
    return (
      <Page>
        <Card>
          <h1 className="text-2xl font-semibold text-fg">{t("app.visitor")}</h1>
          <ErrorAlert>{t("visitor.invalidOrExpired")}</ErrorAlert>
        </Card>
      </Page>
    );
  }

  if (!stream.view) {
    return (
      <Page>
        <Card>
          {stream.errorCode ? <ErrorAlert>{t("visitor.error.generic")}</ErrorAlert> : <p className="text-fg-muted">{t("visitor.loading")}</p>}
        </Card>
      </Page>
    );
  }

  const { view } = stream;
  const canCancel = CANCELLABLE_STATES.has(view.state);

  return (
    <Page>
      {/* The hero card (ticket 65): the token and queue position are the first, largest thing a visitor sees. */}
      <Card className="items-center text-center">
        <StatusBadge status={stream.live ? "up" : "not_configured"}>
          {stream.live
            ? t("visitor.live")
            : t("visitor.lastKnownAsOf", { time: stream.lastUpdatedAt ? formatTime(stream.lastUpdatedAt) : "" })}
        </StatusBadge>
        <p className="text-[3.5rem] font-bold leading-none tabular-nums text-primary">{formatToken(view.token_number)}</p>
        <p className="text-lg font-medium text-fg">{t(`visitor.state.${view.state}`)}</p>
        <p className="text-fg-muted">{view.position !== null ? t("visitor.position", { position: formatNumber(view.position) }) : t("visitor.positionUnknown")}</p>
        <p className="text-fg-muted">
          {view.estimated_wait_minutes
            ? t("visitor.estimatedWait", {
                low: formatNumber(view.estimated_wait_minutes.low),
                high: formatNumber(view.estimated_wait_minutes.high),
              })
            : t("visitor.estimatedWaitUnknown")}
        </p>
        <p className="text-fg-muted">
          {view.now_serving_token_number
            ? t("visitor.nowServing", { token: formatToken(view.now_serving_token_number) })
            : t("visitor.nowServingNone")}
        </p>
      </Card>

      {view.state === "completed" && <FeedbackForm ticketId={ticketId} credential={credential} />}

      {/* The wayfinding card (ticket 65): where to go, once a zone is assigned. */}
      <Card>
        {view.zone ? (
          <>
            <p>{t("visitor.zone.floor", { floor: view.zone.floor_label })}</p>
            {view.zone.building_label && <p>{t("visitor.zone.building", { building: view.zone.building_label })}</p>}
            {view.zone.wayfinding_image_url && (
              <img
                src={view.zone.wayfinding_image_url}
                alt={t("visitor.wayfindingAlt")}
                className="max-h-80 max-w-full rounded-md object-contain"
              />
            )}
          </>
        ) : (
          <p className="text-fg-muted">{t("visitor.zone.none")}</p>
        )}
      </Card>

      {!cancelled && view.state === "remote" && (
        <Card>
          <RemoteCheckIn ticketId={ticketId} credential={credential} qr={openedByQr} />
        </Card>
      )}

      {cancelled ? (
        <Card>
          <p>{t("visitor.cancel.done")}</p>
        </Card>
      ) : canCancel ? (
        <Card>
          {cancelError && <ErrorAlert>{cancelError}</ErrorAlert>}
          <Button className="w-full" variant="secondary" onClick={() => setConfirmingCancel(true)} disabled={cancelling}>
            {cancelling ? t("visitor.cancel.cancelling") : t("visitor.cancel.button")}
          </Button>
          <ConfirmDialog
            open={confirmingCancel}
            title={t("visitor.cancel.button")}
            description={t("visitor.cancel.confirm")}
            confirmLabel={t("common.confirm")}
            cancelLabel={t("common.cancel")}
            onConfirm={() => void cancel()}
            onCancel={() => setConfirmingCancel(false)}
            danger
          />
        </Card>
      ) : null}

      {!cancelled && <Card><PushOptIn ticketId={ticketId} credential={credential} /></Card>}

      <Card>
        {optOutError && <ErrorAlert>{optOutError}</ErrorAlert>}
        <p className="text-fg-muted">{t(optedOut ? "visitor.notifications.optedOut" : "visitor.notifications.intro")}</p>
        <Button className="w-full" variant="secondary" onClick={() => void toggleOptOut()} disabled={optOutBusy}>
          {t(optedOut ? "visitor.notifications.optIn" : "visitor.notifications.optOut")}
        </Button>
      </Card>
    </Page>
  );
}
