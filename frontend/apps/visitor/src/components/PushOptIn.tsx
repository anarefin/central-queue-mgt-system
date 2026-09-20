"use client";

import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert } from "@qms/ui";
import { useState } from "react";
import { useApi } from "../lib/runtime";
import { isIos, isStandalone, pushSupported, rememberTicketReference, SubscribeError, subscribeToPush } from "../lib/webPush";

/**
 * A visitor's own opt-in to Web Push for this ticket (ticket 39, FR-INT-040, §14.1, §18.3, FR-MOB-020): a call,
 * miss, no-show or transfer reaches them even with this page closed. On iOS, Web Push only fires once the page has
 * been added to the Home Screen, so that browser shows the install hint instead of a button until it has been
 * (FR-MOB-020) — the same hint the join screen shows before a ticket exists (ticket 42).
 */
export function PushOptIn({ ticketId, credential }: { ticketId: string; credential: string }) {
  const { t } = useI18n();
  const { client } = useApi();
  const [state, setState] = useState<"idle" | "subscribing" | "subscribed" | "error">("idle");
  const [errorCode, setErrorCode] = useState<"permission_denied" | "generic" | null>(null);

  async function enable() {
    if (!client) return;
    setState("subscribing");
    setErrorCode(null);
    try {
      const { public_key: vapidPublicKey } = await client.webPush.publicKey();
      const subscription = await subscribeToPush(vapidPublicKey);
      if (!subscription.endpoint || !subscription.keys?.p256dh || !subscription.keys?.auth) throw new SubscribeError("error");
      await client.tickets.pushSubscribe(ticketId, credential, {
        endpoint: subscription.endpoint,
        keys: { p256dh: subscription.keys.p256dh, auth: subscription.keys.auth },
      });
      await rememberTicketReference(ticketId, credential);
      setState("subscribed");
    } catch (cause) {
      setState("error");
      setErrorCode(cause instanceof SubscribeError && cause.reason === "permission_denied" ? "permission_denied" : "generic");
    }
  }

  if (isIos() && !isStandalone()) {
    return <p className="qms-muted">{t("visitor.push.iosInstallHint")}</p>;
  }

  if (!pushSupported()) return null;

  if (state === "subscribed") {
    return <p className="qms-muted">{t("visitor.push.enabled")}</p>;
  }

  return (
    <>
      {state === "error" && <ErrorAlert>{t(errorCode === "permission_denied" ? "visitor.push.permissionDenied" : "visitor.push.error")}</ErrorAlert>}
      <Button variant="secondary" onClick={() => void enable()} disabled={state === "subscribing"}>
        {state === "subscribing" ? t("visitor.push.subscribing") : t("visitor.push.enable")}
      </Button>
    </>
  );
}
