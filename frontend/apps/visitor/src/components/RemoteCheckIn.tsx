"use client";

import { ApiRequestError } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, ConfirmDialog, ErrorAlert } from "@qms/ui";
import { useState } from "react";
import { useApi } from "../lib/runtime";

/**
 * A remote ticket's own arrival (ticket 43, FR-MOB-021, FR-MOB-024, FR-MOB-031, §19.1): mark present by tapping
 * check-in inside the Site's geofence, or by the Site's own QR (a drift fallback that skips the distance check,
 * FR-MOB-024) — this page reached via the QR itself proves presence, so `qr` skips straight to confirming rather
 * than asking for location. One optional delay ("not ready yet") per ticket, if the Service allows it (FR-MOB-031).
 */
export function RemoteCheckIn({ ticketId, credential, qr }: { ticketId: string; credential: string; qr: boolean }) {
  const { t } = useI18n();
  const { client } = useApi();
  const [checkingIn, setCheckingIn] = useState(false);
  const [checkInError, setCheckInError] = useState<string | null>(null);
  const [delaying, setDelaying] = useState(false);
  const [delayError, setDelayError] = useState<string | null>(null);
  const [delayed, setDelayed] = useState(false);
  const [confirmingDelay, setConfirmingDelay] = useState(false);

  async function checkIn(method: "qr" | "geofence") {
    if (!client) return;
    setCheckingIn(true);
    setCheckInError(null);
    try {
      if (method === "qr") {
        await client.tickets.checkIn(ticketId, credential, { method: "qr" });
      } else {
        const position = await currentPosition();
        await client.tickets.checkIn(ticketId, credential, {
          method: "geofence",
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
        });
      }
    } catch (cause) {
      setCheckInError(describeCheckInError(cause, t));
    } finally {
      setCheckingIn(false);
    }
  }

  async function delay() {
    setConfirmingDelay(false);
    if (!client) return;
    setDelaying(true);
    setDelayError(null);
    try {
      await client.tickets.delay(ticketId, credential);
      setDelayed(true);
    } catch (cause) {
      const reason = cause instanceof ApiRequestError ? cause.body?.details?.reason : undefined;
      const code = cause instanceof ApiRequestError ? cause.code : "network_error";
      setDelayError(
        reason === "delay_not_allowed" || reason === "delay_already_used" ? t(`visitor.delay.refused.${reason}`) : t(`errors.${code}`),
      );
    } finally {
      setDelaying(false);
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <p>{t("visitor.checkIn.intro")}</p>
      {checkInError && <ErrorAlert>{checkInError}</ErrorAlert>}
      {qr ? (
        <Button className="w-full" onClick={() => void checkIn("qr")} disabled={checkingIn}>
          {checkingIn ? t("visitor.checkIn.checking") : t("visitor.checkIn.confirmQr")}
        </Button>
      ) : (
        <>
          <Button className="w-full" onClick={() => void checkIn("geofence")} disabled={checkingIn}>
            {checkingIn ? t("visitor.checkIn.checking") : t("visitor.checkIn.button")}
          </Button>
          <p className="text-fg-muted">{t("visitor.checkIn.qrHint")}</p>
        </>
      )}
      {!delayed ? (
        <>
          {delayError && <ErrorAlert>{delayError}</ErrorAlert>}
          <Button className="w-full" variant="secondary" onClick={() => setConfirmingDelay(true)} disabled={delaying}>
            {delaying ? t("visitor.delay.delaying") : t("visitor.delay.button")}
          </Button>
          <ConfirmDialog
            open={confirmingDelay}
            title={t("visitor.delay.button")}
            description={t("visitor.delay.confirm")}
            confirmLabel={t("common.confirm")}
            cancelLabel={t("common.cancel")}
            onConfirm={() => void delay()}
            onCancel={() => setConfirmingDelay(false)}
          />
        </>
      ) : (
        <p className="text-fg-muted">{t("visitor.delay.done")}</p>
      )}
    </div>
  );
}

function currentPosition(): Promise<GeolocationPosition> {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error("geolocation_unavailable"));
      return;
    }
    navigator.geolocation.getCurrentPosition(resolve, () => reject(new Error("geolocation_unavailable")), { enableHighAccuracy: true, timeout: 10000 });
  });
}

function describeCheckInError(cause: unknown, t: (key: string, params?: Record<string, string | number>) => string): string {
  if (cause instanceof Error && cause.message === "geolocation_unavailable") return t("visitor.checkIn.refused.geolocation_unavailable");
  const reason = cause instanceof ApiRequestError ? cause.body?.details?.reason : undefined;
  if (reason === "too_far") return t("visitor.checkIn.refused.too_far");
  if (reason === "geofence_not_configured") return t("visitor.checkIn.refused.geofence_not_configured");
  if (reason === "ticket_not_remote") return t("visitor.checkIn.refused.ticket_not_remote");
  const code = cause instanceof ApiRequestError ? cause.code : "network_error";
  return t(`errors.${code}`);
}
