"use client";

import type { NumberingPreview, NumberingRule, NumberingScope } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, ErrorAlert } from "@qms/ui";
import { useState } from "react";
import { useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";
import { NumberingRuleForm } from "./NumberingRuleForm";
import { VersionHistory } from "./VersionHistory";

interface NumberingScopeRowProps {
  scope: NumberingScope;
  id: string;
  name: string;
  /** The scope's own rule, if it has one. */
  rule: NumberingRule | undefined;
  /** What a scope without a rule falls back to: its service group's rule, or the default. */
  fallback: "service_group" | "default";
  /** Names the services of a previewed group; a single service previews under its own name. */
  serviceNames: Record<string, string>;
  onChanged: () => void;
}

/**
 * One Service group or Service in the numbering list: its rule in words, and the actions the API allows: set or change
 * the rule, remove it, and preview the next Token number. A change never renumbers issued tickets (FR-CFG-041), and the
 * screen says how many waiting tickets that concerns.
 */
export function NumberingScopeRow({ scope, id, name, rule, fallback, serviceNames, onChanged }: NumberingScopeRowProps) {
  const { t, language } = useI18n();
  const { client } = useApi();
  const [editing, setEditing] = useState(false);
  const [waiting, setWaiting] = useState<number | null>(null);
  const [preview, setPreview] = useState<NumberingPreview | null>(null);
  const action = useSubmit();

  const separator = rule ? (rule.separator === "" ? t("numbering.separator.empty") : rule.separator) : "";
  const prefix = rule ? (rule.prefix_source === "fixed" ? t("numbering.prefix.fixed", { prefix: rule.fixed_prefix ?? "" }) : t(`numbering.source.${rule.prefix_source}`)) : "";
  const resets = rule
    ? rule.reset_boundary === "never"
      ? t("numbering.resets.never")
      : t("numbering.resets.at", { boundary: t(`numbering.boundary.${rule.reset_boundary}`), time: rule.reset_time })
    : "";
  const summary = rule
    ? t("numbering.summary.rule", { prefix, separator, padding: rule.padding, start: rule.sequence_start, resets })
    : t(fallback === "default" ? "numbering.summary.default" : "numbering.summary.inherits");

  async function remove() {
    await action.run(async () => {
      const change = await client!.numbering.removeRule(scope, id);
      setWaiting(change.affected_waiting_tickets);
      setPreview(null);
      onChanged();
    });
  }

  async function showPreview() {
    await action.run(async () => setPreview(await client!.numbering.preview(scope, id)));
  }

  return (
    <li>
      <div className="qms-stack qms-grow">
        <strong>{name}</strong>
        <span className="qms-muted">{summary}</span>
        <div className="qms-row">
          <Button variant="secondary" type="button" aria-label={`${t(rule ? "numbering.edit" : "numbering.set")} ${name}`} onClick={() => setEditing((v) => !v)}>
            {t(rule ? "numbering.edit" : "numbering.set")}
          </Button>
          {rule && (
            <Button variant="secondary" type="button" aria-label={`${t("numbering.remove")} ${name}`} onClick={remove} disabled={action.busy}>
              {t("numbering.remove")}
            </Button>
          )}
          <Button variant="secondary" type="button" aria-label={`${t("numbering.preview")} ${name}`} onClick={showPreview} disabled={action.busy}>
            {t("numbering.preview")}
          </Button>
        </div>
        {action.error && <ErrorAlert>{action.error}</ErrorAlert>}
        <VersionHistory
          name={name}
          load={() => client!.numbering.versions(scope, id)}
          revert={(versionId) => client!.numbering.revert(scope, id, versionId)}
          onReverted={() => {
            setWaiting(null);
            setPreview(null);
            onChanged();
          }}
        />
        {editing && (
          <NumberingRuleForm
            initial={rule}
            onSubmit={async (input) => {
              const change = await client!.numbering.setRule(scope, id, input);
              setWaiting(change.affected_waiting_tickets);
              setPreview(null);
              onChanged();
            }}
            onDone={() => setEditing(false)}
            onCancel={() => setEditing(false)}
          />
        )}
        {waiting !== null && (
          <span className="qms-warning" role="status">
            {waiting > 0 ? t("numbering.warning.waiting", { count: waiting }) : t("numbering.warning.none")}
          </span>
        )}
        {preview && preview.items.length === 0 && <span className="qms-muted">{t("numbering.preview.none")}</span>}
        {preview?.items.map((item) => (
          <span key={item.service_id} role="status">
            {t("numbering.preview.next", { service: scope === "service" ? name : (serviceNames[item.service_id] ?? name), token: item.token_number })}{" "}
            <span className="qms-muted">
              {item.next_reset_at
                ? t("numbering.preview.reset", { when: new Date(item.next_reset_at).toLocaleString(language) })
                : t("numbering.preview.never")}
            </span>
          </span>
        ))}
      </div>
    </li>
  );
}
