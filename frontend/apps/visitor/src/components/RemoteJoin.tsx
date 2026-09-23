"use client";

import { ApiRequestError, newIdempotencyKey, type RemoteJoinPolicy } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, Page } from "@qms/ui";
import { useCallback, useEffect, useState } from "react";
import { BASE_PATH, useApi } from "../lib/runtime";
import { useAccount } from "../lib/visitorAuth";
import { VisitorLogin } from "./VisitorLogin";

/** The `conflict`/`service_closed` reasons this screen has its own wording for (SRS §20.3's own "branch on the code
 * and the reason, never the message" shape, the same one {@code ReceptionDesk.tsx} already uses). */
const REFUSALS = new Set([
  "virtual_queue_disabled",
  "too_far",
  "remote_share_full",
  "too_early",
  "outside_hours",
  "holiday",
  "past_cutoff",
  "cap_reached",
  "maintenance",
  "service_inactive",
  "channel_not_allowed",
  "no_agent_rostered",
  "duplicate_ticket",
  "site_location_unset",
  "internet_unreachable",
]);

/** The visitor's own device position, or null when geolocation is unavailable, denied, or times out. */
function locate(): Promise<{ latitude: number; longitude: number } | null> {
  if (typeof navigator === "undefined" || !navigator.geolocation) return Promise.resolve(null);
  return new Promise((resolve) => {
    navigator.geolocation.getCurrentPosition(
      (position) => resolve({ latitude: position.coords.latitude, longitude: position.coords.longitude }),
      () => resolve(null),
      { timeout: 10_000 },
    );
  });
}

/**
 * A registered visitor joining a Service's queue remotely, before arriving (ticket 42, SRS §13.2, FR-MOB-010): shows
 * the Service's policy and the forfeit consequences before the visitor commits (FR-MOB-023), asks for the device's
 * position only when the policy sets a distance cap, then joins and hands off to the same ticket-status view the
 * kiosk's own printer-failure QR already opens (ticket 37) — the joined Ticket is shown no differently for having
 * started `remote` instead of `waiting`.
 */
export function RemoteJoin({ serviceId }: { serviceId: string }) {
  const { t } = useI18n();
  const { client, error: configError } = useApi();
  const { status } = useAccount();
  const [policy, setPolicy] = useState<RemoteJoinPolicy | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [joining, setJoining] = useState(false);
  const [joinError, setJoinError] = useState<string | null>(null);

  const loadPolicy = useCallback(async () => {
    if (!client || status !== "authenticated") return;
    try {
      setPolicy(await client.remoteJoin.policy(serviceId));
    } catch {
      setLoadError(t("remoteJoin.error.generic"));
    }
  }, [client, status, serviceId, t]);

  useEffect(() => {
    void loadPolicy();
  }, [loadPolicy]);

  async function join() {
    if (!client || !policy) return;
    setJoining(true);
    setJoinError(null);
    try {
      const needsPosition = policy.max_distance_m !== null;
      const position = needsPosition ? await locate() : null;
      if (needsPosition && !position) {
        setJoinError(t("remoteJoin.error.locationRequired"));
        return;
      }
      const ticket = await client.remoteJoin.join(serviceId, position ?? {}, newIdempotencyKey());
      const url = new URL(`${window.location.origin}${BASE_PATH}/`);
      url.searchParams.set("t", ticket.id);
      window.location.href = `${url.toString()}#s=${encodeURIComponent(ticket.secret ?? "")}`;
    } catch (cause) {
      const reason = cause instanceof ApiRequestError ? cause.body?.details?.reason : undefined;
      if (cause instanceof ApiRequestError && typeof reason === "string" && REFUSALS.has(reason)) {
        setJoinError(t(`remoteJoin.refused.${reason}`));
      } else {
        const code = cause instanceof ApiRequestError ? cause.code : "network_error";
        setJoinError(t(`errors.${code}`));
      }
    } finally {
      setJoining(false);
    }
  }

  if (configError) {
    return (
      <Page>
        <Card>
          <ErrorAlert>{t("remoteJoin.error.generic")}</ErrorAlert>
        </Card>
      </Page>
    );
  }

  if (status !== "authenticated") {
    return (
      <Page>
        <Card>
          <h1 className="text-2xl font-semibold text-fg">{t("remoteJoin.title")}</h1>
          <p className="text-fg-muted">{t("remoteJoin.signInFirst")}</p>
        </Card>
        <VisitorLogin />
      </Page>
    );
  }

  if (loadError) {
    return (
      <Page>
        <Card>
          <ErrorAlert>{loadError}</ErrorAlert>
        </Card>
      </Page>
    );
  }

  if (!policy) {
    return (
      <Page>
        <Card>
          <p className="text-fg-muted">{t("common.loading")}</p>
        </Card>
      </Page>
    );
  }

  if (policy.internet_available === false) {
    return (
      <Page>
        <Card>
          <h1 className="text-2xl font-semibold text-fg">{t("remoteJoin.title")}</h1>
          <ErrorAlert>{t("remoteJoin.refused.internet_unreachable")}</ErrorAlert>
        </Card>
      </Page>
    );
  }

  if (!policy.virtual_queue_enabled) {
    return (
      <Page>
        <Card>
          <h1 className="text-2xl font-semibold text-fg">{t("remoteJoin.title")}</h1>
          <ErrorAlert>{t("remoteJoin.refused.virtual_queue_disabled")}</ErrorAlert>
        </Card>
      </Page>
    );
  }

  return (
    <Page>
      <Card>
        <h1 className="text-2xl font-semibold text-fg">{t("remoteJoin.title")}</h1>
        <p>{t("remoteJoin.intro")}</p>
        {policy.max_distance_m !== null && (
          <p className="text-fg-muted">{t("remoteJoin.policy.distance", { km: Math.round(policy.max_distance_m / 100) / 10 })}</p>
        )}
        <p className="text-fg-muted">{t("remoteJoin.policy.window", { minutes: policy.join_window_minutes })}</p>
      </Card>

      <Card>
        <h2 className="text-lg font-semibold text-fg">{t("remoteJoin.forfeit.title")}</h2>
        <p>{t("remoteJoin.forfeit.body", { minutes: policy.arrival_deadline_minutes })}</p>
      </Card>

      <Card>
        {joinError && <ErrorAlert>{joinError}</ErrorAlert>}
        <Button className="w-full" onClick={() => void join()} disabled={joining}>
          {joining ? t("remoteJoin.joining") : t("remoteJoin.join")}
        </Button>
      </Card>
    </Page>
  );
}
