"use client";

import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert } from "@qms/ui";
import { useState, type ReactNode } from "react";
import { ActiveBadge, useConfirmDialog, useSubmit } from "../lib/admin-support";

interface EntityRowProps {
  /** Shown as the heading and used to tell the buttons of one row from another's. */
  name: string;
  heading: ReactNode;
  lines: string[];
  /** Non-blocking notices, such as a missing translation (FR-I18N-010). */
  warnings?: string[];
  active: boolean;
  /** False for a record that must stay active, such as the default priority class; the button is left out. */
  canDeactivate?: boolean;
  confirmText: string;
  onDeactivate: () => Promise<unknown>;
  onActivate: () => Promise<unknown>;
  /** Renders the edit form; call `close` when it is saved or cancelled. */
  editForm: (close: () => void) => ReactNode;
  /** Extra buttons, such as the drill-down into a site's zones. */
  extra?: ReactNode;
  selected?: boolean;
}

/**
 * One site, zone or counter in a list: its facts, and the actions the API allows. Deactivation asks first because it
 * also takes everything below the record; nothing here can delete (FR-CFG-001).
 */
export function EntityRow({ name, heading, lines, warnings = [], active, canDeactivate = true, confirmText, onDeactivate, onActivate, editForm, extra, selected }: EntityRowProps) {
  const { t } = useI18n();
  const [editing, setEditing] = useState(false);
  const { busy, error, run } = useSubmit();
  const { ask, dialog } = useConfirmDialog();

  function confirmDeactivate() {
    ask({
      title: `${t("admin.action.confirmDeactivate")} ${name}`,
      description: confirmText,
      confirmLabel: t("admin.action.confirmDeactivate"),
      danger: true,
      onConfirm: () => void run(onDeactivate),
    });
  }

  return (
    <li aria-current={selected ? "true" : undefined}>
      <div className="flex flex-col gap-4 flex-1 min-w-0">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <strong>{heading}</strong>
          <ActiveBadge active={active} />
        </div>
        {lines.map((line, index) => (
          <span className="text-fg-muted" key={index}>
            {line}
          </span>
        ))}
        {warnings.map((warning, index) => (
          <span className="text-warn" role="status" key={index}>
            {warning}
          </span>
        ))}
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            {extra}
            <Button variant="secondary" type="button" aria-label={`${t("admin.action.edit")} ${name}`} onClick={() => setEditing((v) => !v)}>
              {t("admin.action.edit")}
            </Button>
            {active ? (
              canDeactivate && (
                <Button variant="secondary" type="button" aria-label={`${t("admin.action.deactivate")} ${name}`} onClick={confirmDeactivate}>
                  {t("admin.action.deactivate")}
                </Button>
              )
            ) : (
              <Button variant="secondary" type="button" aria-label={`${t("admin.action.activate")} ${name}`} disabled={busy} onClick={() => void run(onActivate)}>
                {t("admin.action.activate")}
              </Button>
            )}
          </div>
        </div>
        {error && <ErrorAlert>{error}</ErrorAlert>}
        {editing && editForm(() => setEditing(false))}
      </div>
      {dialog}
    </li>
  );
}
