"use client";

import { ApiRequestError } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert } from "@qms/ui";
import { useState } from "react";
import { useApi } from "../lib/runtime";

type State = { kind: "idle" } | { kind: "downloading" } | { kind: "error"; code: string };

/** Saves `blob` to disk under `filename`, the same object-URL-anchor trick every other export uses. */
function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}

/** The one-click support diagnostics bundle (ticket 60, FR-OPS-040, §26.5): System Administrator only. */
export function DiagnosticsPanel() {
  const { t } = useI18n();
  const { client } = useApi();
  const [state, setState] = useState<State>({ kind: "idle" });

  async function download() {
    if (!client) return;
    setState({ kind: "downloading" });
    try {
      const ready = await client.ops.diagnostics();
      downloadBlob(ready.blob, ready.filename);
      setState({ kind: "idle" });
    } catch (cause: unknown) {
      setState({ kind: "error", code: cause instanceof ApiRequestError ? cause.code : "network_error" });
    }
  }

  return (
    <Card>
      <div className="qms-row">
        <h2 className="qms-heading">{t("ops.diagnostics.title")}</h2>
        <Button variant="secondary" type="button" onClick={download} disabled={state.kind === "downloading"}>
          {t("ops.diagnostics.download")}
        </Button>
      </div>
      <p className="qms-muted">{t("ops.diagnostics.intro")}</p>
      {state.kind === "error" && (
        <ErrorAlert>
          {t("ops.diagnostics.failed")} {t(`errors.${state.code}`)}
        </ErrorAlert>
      )}
    </Card>
  );
}
