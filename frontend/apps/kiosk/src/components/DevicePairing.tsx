"use client";

import { ApiRequestError, type ApiClient, type DeviceBootstrap } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { applyBrand, Button, Card, ErrorAlert, Page, TextField } from "@qms/ui";
import { useEffect, useId, useState, type FormEvent } from "react";
import { KioskFlow } from "./KioskFlow";
import { useApi } from "../lib/runtime";

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

  // The kiosk has no theme toggle and no separate branding fetch (ticket 62): whatever its own device bootstrap
  // already carries is what applyBrand runs on, every time a fresh bootstrap arrives.
  useEffect(() => {
    if (bootstrap) applyBrand(bootstrap.branding);
  }, [bootstrap]);

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
  if (!session || status === "unknown") return <p className="text-fg-muted">{t("common.loading")}</p>;
  if (status === "unpaired") return <PairingForm />;
  return <PairedView bootstrap={bootstrap} bootstrapError={bootstrapError} client={client} />;
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
        <h1 className="text-2xl font-semibold text-fg">{t("devicePairing.title")}</h1>
        <p className="text-fg-muted">{t("devicePairing.instructions")}</p>
        <form className="flex flex-col gap-4" onSubmit={submit}>
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
  client,
}: {
  bootstrap: DeviceBootstrap | null;
  bootstrapError: boolean;
  client: ApiClient | null;
}) {
  const { t } = useI18n();
  if (bootstrapError) {
    return (
      <Page>
        <Card>
          <ErrorAlert>{t("devicePairing.configError")}</ErrorAlert>
        </Card>
      </Page>
    );
  }
  if (!bootstrap || !client) {
    return (
      <Page>
        <Card>
          <p className="text-fg-muted">{t("devicePairing.loadingConfig")}</p>
        </Card>
      </Page>
    );
  }
  // KioskFlow renders the site's own brand header (logo/org name, ticket 65) and the single `<main>` landmark for
  // the whole flow; nothing needs to be shown around it once paired, so the site name — the loaded-bootstrap marker
  // the pairing test looks for — appears there instead of a separate heading here. Terminology remapping (ticket 69)
  // is wired inside KioskFlow itself, alongside its own nested I18nProvider.
  return <KioskFlow bootstrap={bootstrap} client={client} />;
}
