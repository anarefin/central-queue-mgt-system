"use client";

import type { ConfigVersionView, Items } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert } from "@qms/ui";
import { useState } from "react";
import { describeError, useConfirmDialog, useSubmit } from "../lib/admin-support";

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
  const { error, run } = useSubmit();
  const { ask, dialog } = useConfirmDialog();

  function confirmRevert(version: ConfigVersionView) {
    ask({
      title: `${t("admin.versionHistory.revert")} ${name} ${new Date(version.changed_at).toLocaleString(language)}`,
      description: t("admin.versionHistory.confirmRevert"),
      confirmLabel: t("admin.versionHistory.confirmRevertAction"),
      danger: true,
      onConfirm: () => {
        void run(() => revert(version.id)).then((ok) => {
          if (!ok) return;
          setOpen(false);
          onReverted();
        });
      },
    });
  }

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
    <div className="flex flex-col gap-4">
      <Button variant="secondary" type="button" aria-label={`${t("admin.versionHistory.toggle")} ${name}`} onClick={() => void toggle()}>
        {t("admin.versionHistory.toggle")}
      </Button>
      {open && (
        <div className="flex flex-col gap-4" role="group" aria-label={`${t("admin.versionHistory.title")} ${name}`}>
          {loadError !== null && <ErrorAlert>{describeError(t, loadError)}</ErrorAlert>}
          {versions !== null && versions.length === 0 && <p className="text-fg-muted">{t("admin.versionHistory.empty")}</p>}
          {versions !== null && versions.length > 0 && (
            <ul className="m-0 list-none p-0 flex flex-col divide-y divide-border [&>li]:flex [&>li]:flex-wrap [&>li]:items-center [&>li]:justify-between [&>li]:gap-2 [&>li]:py-2.5">
              {versions.map((version) => (
                <li key={version.id} className="flex flex-wrap items-center justify-between gap-3">
                  <span className="text-fg-muted">{new Date(version.changed_at).toLocaleString(language)}</span>
                  <Button
                    variant="secondary"
                    type="button"
                    aria-label={`${t("admin.versionHistory.revert")} ${name} ${new Date(version.changed_at).toLocaleString(language)}`}
                    onClick={() => confirmRevert(version)}
                  >
                    {t("admin.versionHistory.revert")}
                  </Button>
                </li>
              ))}
            </ul>
          )}
          {error && <ErrorAlert>{error}</ErrorAlert>}
        </div>
      )}
      {dialog}
    </div>
  );
}
