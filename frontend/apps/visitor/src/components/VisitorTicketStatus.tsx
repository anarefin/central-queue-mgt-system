"use client";

import { ApiRequestError } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, Page, StatusBadge } from "@qms/ui";
import { useState } from "react";
import { useApi } from "../lib/runtime";
import { useTicketStream, type TicketStreamDeps } from "../lib/ticketStream";

/** A visitor may cancel any time before being called (FR-MOB-030); once called, serving or held, the button is gone. */
const CANCELLABLE_STATES = new Set(["remote", "waiting", "paused"]);

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
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);
  const [cancelled, setCancelled] = useState(false);

  async function cancel() {
    if (!client || !window.confirm(t("visitor.cancel.confirm"))) return;
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
          <h1 className="qms-heading">{t("app.visitor")}</h1>
          <ErrorAlert>{t("visitor.invalidOrExpired")}</ErrorAlert>
        </Card>
      </Page>
    );
  }

  if (!stream.view) {
    return (
      <Page>
        <Card>
          {stream.errorCode ? <ErrorAlert>{t("visitor.error.generic")}</ErrorAlert> : <p className="qms-muted">{t("visitor.loading")}</p>}
        </Card>
      </Page>
    );
  }

  const { view } = stream;
  const canCancel = CANCELLABLE_STATES.has(view.state);

  return (
    <Page>
      <Card>
        <h1 className="qms-heading">{formatToken(view.token_number)}</h1>
        <StatusBadge status={stream.live ? "up" : "not_configured"}>
          {stream.live
            ? t("visitor.live")
            : t("visitor.lastKnownAsOf", { time: stream.lastUpdatedAt ? formatTime(stream.lastUpdatedAt) : "" })}
        </StatusBadge>
        <p>{t(`visitor.state.${view.state}`)}</p>
        <p>{view.position !== null ? t("visitor.position", { position: formatNumber(view.position) }) : t("visitor.positionUnknown")}</p>
        <p>
          {view.estimated_wait_minutes
            ? t("visitor.estimatedWait", {
                low: formatNumber(view.estimated_wait_minutes.low),
                high: formatNumber(view.estimated_wait_minutes.high),
              })
            : t("visitor.estimatedWaitUnknown")}
        </p>
        <p>
          {view.now_serving_token_number
            ? t("visitor.nowServing", { token: formatToken(view.now_serving_token_number) })
            : t("visitor.nowServingNone")}
        </p>
      </Card>

      <Card>
        {view.zone ? (
          <>
            <p>{t("visitor.zone.floor", { floor: view.zone.floor_label })}</p>
            {view.zone.building_label && <p>{t("visitor.zone.building", { building: view.zone.building_label })}</p>}
            {view.zone.wayfinding_image_url && <img src={view.zone.wayfinding_image_url} alt={t("visitor.wayfindingAlt")} className="qms-wayfinding" />}
          </>
        ) : (
          <p className="qms-muted">{t("visitor.zone.none")}</p>
        )}
      </Card>

      {cancelled ? (
        <Card>
          <p>{t("visitor.cancel.done")}</p>
        </Card>
      ) : canCancel ? (
        <Card>
          {cancelError && <ErrorAlert>{cancelError}</ErrorAlert>}
          <Button variant="secondary" onClick={() => void cancel()} disabled={cancelling}>
            {cancelling ? t("visitor.cancel.cancelling") : t("visitor.cancel.button")}
          </Button>
        </Card>
      ) : null}
    </Page>
  );
}
