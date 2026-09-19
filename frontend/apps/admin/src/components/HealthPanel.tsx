"use client";

import { ApiRequestError, type DependencyHealth } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, StatusBadge, type StatusKind } from "@qms/ui";
import { useCallback, useEffect, useState } from "react";
import { useApi } from "../lib/runtime";

type State =
  | { kind: "loading" }
  | { kind: "ok"; health: DependencyHealth }
  | { kind: "error"; code: string };

const DEPENDENCIES = ["database", "realtime_hub", "notification_gateway"] as const;

/** Shows backend and dependency health, fetched through the shared api-client. */
export function HealthPanel() {
  const { t } = useI18n();
  const { client, error: configError } = useApi();
  const [state, setState] = useState<State>({ kind: "loading" });

  const load = useCallback(() => {
    if (!client) return;
    setState({ kind: "loading" });
    client.health.dependencies().then(
      (health) => setState({ kind: "ok", health }),
      (cause: unknown) => setState({ kind: "error", code: cause instanceof ApiRequestError ? cause.code : "network_error" }),
    );
  }, [client]);

  useEffect(load, [load]);

  const status = (value: StatusKind) => <StatusBadge status={value}>{t(`admin.health.${value}`)}</StatusBadge>;

  return (
    <Card>
      <div className="qms-row">
        <h2 className="qms-heading">{t("admin.health.title")}</h2>
        <Button variant="secondary" type="button" onClick={load}>
          {t("common.retry")}
        </Button>
      </div>

      {configError && <ErrorAlert>{t("admin.health.unreachable")}</ErrorAlert>}
      {!configError && state.kind === "loading" && <p className="qms-muted">{t("admin.health.checking")}</p>}
      {state.kind === "error" && (
        <ErrorAlert>
          {t("admin.health.unreachable")} {t(`errors.${state.code}`)}
        </ErrorAlert>
      )}
      {state.kind === "ok" && (
        <ul className="qms-list">
          <li>
            <span>{t("admin.health.backend")}</span>
            {status(state.health.status)}
          </li>
          {DEPENDENCIES.map((name) => (
            <li key={name}>
              <span>{t(`admin.health.${name}`)}</span>
              {status(state.health.dependencies[name]?.status ?? "not_configured")}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
