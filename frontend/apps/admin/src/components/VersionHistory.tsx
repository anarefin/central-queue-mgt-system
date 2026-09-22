"use client";

import type { ConfigVersionView, Items } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert } from "@qms/ui";
import { useState } from "react";
import { describeError, useSubmit } from "../lib/admin-support";

interface VersionHistoryProps {
  /** Identifies the row this history belongs to, for accessible labels. */
  name: string;
  load: () => Promise<Items<ConfigVersionView>>;
  revert: (versionId: string) => Promise<unknown>;
  /** Called after a successful revert, so the caller can reload the reverted record. */
  onReverted: () => void;
}

/**
 * The version history and revert action FR-CFG-040 asks for on a versioned config area (Priority classes and
 * routing strategy, numbering rules, business hours): collapsed by default, fetched only once opened, newest first,
 * each past state revertible with a confirm step (mirroring {@link EntityRow}'s own deactivate-confirm pattern).
 */
export function VersionHistory({ name, load, revert, onReverted }: VersionHistoryProps) {
  const { t, language } = useI18n();
  const [open, setOpen] = useState(false);
  const [versions, setVersions] = useState<ConfigVersionView[] | null>(null);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const { busy, error, run } = useSubmit();

  async function toggle() {
    if (open) {
      setOpen(false);
      return;
    }
    setOpen(true);
    setLoadError(null);
    try {
      const page = await load();
      setVersions(page.items);
    } catch (cause) {
      setLoadError(cause);
    }
  }

  return (
    <div className="qms-stack">
      <Button variant="secondary" type="button" aria-label={`${t("admin.versionHistory.toggle")} ${name}`} onClick={() => void toggle()}>
        {t("admin.versionHistory.toggle")}
      </Button>
      {open && (
        <div className="qms-stack" role="group" aria-label={`${t("admin.versionHistory.title")} ${name}`}>
          {loadError !== null && <ErrorAlert>{describeError(t, loadError)}</ErrorAlert>}
          {versions !== null && versions.length === 0 && <p className="qms-muted">{t("admin.versionHistory.empty")}</p>}
          {versions !== null && versions.length > 0 && (
            <ul className="qms-list">
              {versions.map((version) => (
                <li key={version.id} className="qms-row">
                  <span className="qms-muted">{new Date(version.changed_at).toLocaleString(language)}</span>
                  {confirmingId === version.id ? (
                    <span className="qms-row" role="group" aria-label={`${t("admin.versionHistory.confirmRevert")} ${name}`}>
                      <span>{t("admin.versionHistory.confirmRevert")}</span>
                      <Button
                        type="button"
                        disabled={busy}
                        onClick={async () => {
                          if (await run(() => revert(version.id))) {
                            setConfirmingId(null);
                            setOpen(false);
                            onReverted();
                          }
                        }}
                      >
                        {t("admin.versionHistory.confirmRevertAction")}
                      </Button>
                      <Button variant="secondary" type="button" onClick={() => setConfirmingId(null)}>
                        {t("admin.action.cancel")}
                      </Button>
                    </span>
                  ) : (
                    <Button
                      variant="secondary"
                      type="button"
                      aria-label={`${t("admin.versionHistory.revert")} ${name} ${new Date(version.changed_at).toLocaleString(language)}`}
                      onClick={() => setConfirmingId(version.id)}
                    >
                      {t("admin.versionHistory.revert")}
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}
          {error && <ErrorAlert>{error}</ErrorAlert>}
        </div>
      )}
    </div>
  );
}
