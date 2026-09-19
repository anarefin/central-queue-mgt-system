"use client";

import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert } from "@qms/ui";
import { useState, type ReactNode } from "react";
import { ActiveBadge, useSubmit } from "../lib/admin-support";

interface EntityRowProps {
  /** Shown as the heading and used to tell the buttons of one row from another's. */
  name: string;
  heading: ReactNode;
  lines: string[];
  /** Non-blocking notices, such as a missing translation (FR-I18N-010). */
  warnings?: string[];
  active: boolean;
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
export function EntityRow({ name, heading, lines, warnings = [], active, confirmText, onDeactivate, onActivate, editForm, extra, selected }: EntityRowProps) {
  const { t } = useI18n();
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const { busy, error, run } = useSubmit();

  return (
    <li aria-current={selected ? "true" : undefined}>
      <div className="qms-stack qms-grow">
        <div className="qms-row">
          <strong>{heading}</strong>
          <ActiveBadge active={active} />
        </div>
        {lines.map((line, index) => (
          <span className="qms-muted" key={index}>
            {line}
          </span>
        ))}
        {warnings.map((warning, index) => (
          <span className="qms-warning" role="status" key={index}>
            {warning}
          </span>
        ))}
        <div className="qms-row">
          <div className="qms-row">
            {extra}
            <Button variant="secondary" type="button" aria-label={`${t("admin.action.edit")} ${name}`} onClick={() => setEditing((v) => !v)}>
              {t("admin.action.edit")}
            </Button>
            {active ? (
              <Button variant="secondary" type="button" aria-label={`${t("admin.action.deactivate")} ${name}`} onClick={() => setConfirming(true)}>
                {t("admin.action.deactivate")}
              </Button>
            ) : (
              <Button variant="secondary" type="button" aria-label={`${t("admin.action.activate")} ${name}`} disabled={busy} onClick={() => void run(onActivate)}>
                {t("admin.action.activate")}
              </Button>
            )}
          </div>
        </div>
        {confirming && (
          <div className="qms-stack" role="group" aria-label={`${t("admin.action.confirmDeactivate")} ${name}`}>
            <p>{confirmText}</p>
            <div className="qms-row">
              <Button
                type="button"
                disabled={busy}
                onClick={async () => {
                  if (await run(onDeactivate)) setConfirming(false);
                }}
              >
                {t("admin.action.confirmDeactivate")}
              </Button>
              <Button variant="secondary" type="button" onClick={() => setConfirming(false)}>
                {t("admin.action.cancel")}
              </Button>
            </div>
          </div>
        )}
        {error && <ErrorAlert>{error}</ErrorAlert>}
        {editing && editForm(() => setEditing(false))}
      </div>
    </li>
  );
}
