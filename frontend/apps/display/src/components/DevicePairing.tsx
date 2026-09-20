"use client";

import { ApiRequestError, type DeviceBootstrap } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, Page, TextField } from "@qms/ui";
import { useEffect, useId, useState, type FormEvent } from "react";
import { useApi } from "../lib/runtime";
import { DisplayBoard } from "./DisplayBoard";

/** How often a paired device reports in (FR-OPS-041); the SRS gives no number, this build's own choice. */
const HEARTBEAT_INTERVAL_MS = 60_000;
const APP_VERSION = process.env.NEXT_PUBLIC_APP_VERSION ?? "0.0.0";

/**
 * The whole of a kiosk's screen until it has real work to do (ticket 24): pair with a code from an administrator,
 * then load its bootstrap configuration, report a heartbeat on a timer, and react to a pushed reload or
 * configuration-change command on its own `device:{id}` realtime topic (FR-OPS-011, FR-OPS-041, FR-OPS-042).
 */
export function DevicePairing() {
  const { t } = useI18n();
  const { client, session, realtime, error: configError } = useApi();
  const [status, setStatus] = useState(session?.status ?? "unknown");
  const [bootstrap, setBootstrap] = useState<DeviceBootstrap | null>(null);
  const [bootstrapError, setBootstrapError] = useState(false);

  useEffect(() => {
    if (!session) return;
    setStatus(session.status);
    const unsubscribe = session.subscribe(() => setStatus(session.status));
    session.restore().catch(() => undefined);
    return unsubscribe;
  }, [session]);

  useEffect(() => {
    if (!client || !session || status !== "paired" || !session.device) return;
    const deviceId = session.device.id;
    let cancelled = false;
    function loadBootstrap() {
      setBootstrapError(false);
      client!.devices.bootstrap().then(
        (result) => !cancelled && setBootstrap(result),
        () => !cancelled && setBootstrapError(true),
      );
    }
    function beat() {
      client!.devices.heartbeat(deviceId, APP_VERSION).catch(() => undefined);
    }
    loadBootstrap();
    beat();
    const interval = setInterval(beat, HEARTBEAT_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [client, session, status]);

  useEffect(() => {
    if (!realtime || !client || !session || status !== "paired" || !session.device) return;
    return realtime.subscribe(`device:${session.device.id}`, (update) => {
      if (update.kind !== "event") return;
      if (update.type === "device.command" && update.data.command === "reload") {
        window.location.reload();
      } else if (update.type === "config.changed") {
        client.devices.bootstrap().then(setBootstrap, () => setBootstrapError(true));
      }
    });
  }, [realtime, client, session, status]);

  if (configError) return <ErrorAlert>{t("errors.network_error")}</ErrorAlert>;
  if (!session || status === "unknown") return <p className="qms-muted">{t("common.loading")}</p>;
  if (status === "unpaired") return <PairingForm />;
  return <PairedView bootstrap={bootstrap} bootstrapError={bootstrapError} deviceId={session.device?.id ?? null} />;
}

function PairingForm() {
  const { t } = useI18n();
  const { session } = useApi();
  const id = useId();
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await session!.pair(code.trim());
    } catch (cause) {
      setError(cause instanceof ApiRequestError ? t(`errors.${cause.code}`) : t("errors.network_error"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Page>
      <Card>
        <h1 className="qms-heading">{t("devicePairing.title")}</h1>
        <p className="qms-muted">{t("devicePairing.instructions")}</p>
        <form className="qms-stack" onSubmit={submit}>
          <TextField
            id={`${id}-code`}
            label={t("devicePairing.codeLabel")}
            value={code}
            onChange={(e) => setCode(e.target.value.toUpperCase())}
          />
          {error && <ErrorAlert>{error}</ErrorAlert>}
          <Button type="submit" disabled={busy || code.trim() === ""}>
            {busy ? t("devicePairing.pairing") : t("devicePairing.submit")}
          </Button>
        </form>
      </Card>
    </Page>
  );
}

function PairedView({
  bootstrap,
  bootstrapError,
  deviceId,
}: {
  bootstrap: DeviceBootstrap | null;
  bootstrapError: boolean;
  deviceId: string | null;
}) {
  const { t } = useI18n();
  // The organisation's branding (ticket 27, FR-CFG-030): applied here the same way the kiosk applies it, so the
  // display board carries the same logo, colour and name as the now-serving table it wraps (ticket 28).
  const style = bootstrap?.branding.primary_color ? { ["--qms-color-primary" as string]: bootstrap.branding.primary_color } : undefined;
  return (
    <Page>
      <div className="qms-stack" style={style}>
        {bootstrapError && (
          <Card>
            <ErrorAlert>{t("devicePairing.configError")}</ErrorAlert>
          </Card>
        )}
        {!bootstrap && !bootstrapError && (
          <Card>
            <p className="qms-muted">{t("devicePairing.loadingConfig")}</p>
          </Card>
        )}
        {bootstrap && (
          <header className="qms-now-serving-header">
            {bootstrap.branding.logo_url && (
              <img
                className="qms-brand-logo"
                src={bootstrap.branding.logo_url}
                alt={t("devicePairing.logoAlt", { org: bootstrap.branding.org_name ?? bootstrap.branding.site_name })}
              />
            )}
            <h1 className="qms-heading">{bootstrap.branding.site_name}</h1>
          </header>
        )}
        {/* FR-DSP-001: only a display is paired to a zone; a kiosk's bootstrap.layout is null and has no board to show. */}
        {bootstrap?.layout && deviceId && <DisplayBoard deviceId={deviceId} />}
      </div>
    </Page>
  );
}
