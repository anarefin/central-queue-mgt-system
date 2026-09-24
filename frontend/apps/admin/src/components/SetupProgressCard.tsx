"use client";

import type { SetupState } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Badge, Card, ErrorAlert, Loading } from "@qms/ui";
import Link from "next/link";
import { useEffect, useState } from "react";
import { describeError } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

const STEPS = ["org_and_sites", "zones_and_counters", "services_and_numbering", "users_and_roles", "devices_registered"] as const;

/** Each step's own i18n key under `setup.step.*`, shared with `SetupWizard`. */
const STEP_LABEL_KEY: Record<(typeof STEPS)[number], string> = {
  org_and_sites: "setup.step.orgAndSites",
  zones_and_counters: "setup.step.zonesAndCounters",
  services_and_numbering: "setup.step.servicesAndNumbering",
  users_and_roles: "setup.step.usersAndRoles",
  devices_registered: "setup.step.devices",
};

/**
 * The overview page's compact setup summary (ticket 63, SRS §26.2): how many of `GET /setup/state`'s own steps
 * are done, with a link to the full wizard. The wizard itself (`SetupWizard`) still owns each step's detail.
 */
export function SetupProgressCard() {
  const { t } = useI18n();
  const { client } = useApi();
  const [state, setState] = useState<SetupState | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client.setup.state().then(
      (result) => !cancelled && setState(result),
      (cause: unknown) => !cancelled && setError(cause),
    );
    return () => {
      cancelled = true;
    };
  }, [client]);

  const done = state ? STEPS.filter((step) => state[step]).length : 0;

  return (
    <Card header={t("admin.overview.setup.title")} actions={<Link href="/setup/">{t("admin.overview.setup.open")}</Link>}>
      {error !== null && <ErrorAlert>{describeError(t, error)}</ErrorAlert>}
      {error === null && !state && <Loading>{t("setup.profile.loading")}</Loading>}
      {state && (
        <>
          <p className="text-fg-muted">{t("admin.overview.setup.progress", { done, total: STEPS.length })}</p>
          <div className="flex flex-wrap gap-2">
            {STEPS.map((step) => (
              <Badge key={step} variant={state[step] ? "ok" : "neutral"}>
                {t(STEP_LABEL_KEY[step])}
              </Badge>
            ))}
          </div>
        </>
      )}
    </Card>
  );
}
