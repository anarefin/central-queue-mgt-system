"use client";

import { RETENTION_DATA_CLASSES, type RetentionDataClass, type RetentionMode, type RetentionPolicy } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField, TextField } from "@qms/ui";
import { useEffect, useId, useState } from "react";
import { describeError, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

const MODES: RetentionMode[] = ["anonymize", "purge"];

/**
 * Retention, purge and BI access (ticket 53, SRS §16.3, §25.4-25.5; FR-RPT-021/022, FR-SEC-032/043): how long
 * each data class's own data is kept before the nightly purge job acts on it, and, for `ticket_detail` alone,
 * whether it is purged outright or reduced to an anonymised aggregate. Purging itself and the client's own BI
 * user/nightly extract are server-side/operational (RetentionPurgeRunner, ReportingExtractRunner, `bi.
 * ticket_fact_v1`, docs/bi-access.md); this card only lets an admin see and change the policy each sweep reads.
 */
export function RetentionPolicyCard() {
  const { t } = useI18n();
  const { client } = useApi();
  const policies = useList<RetentionPolicy>(client ? () => client.retention.policies() : null, [client]);

  return (
    <Card>
      <h2 className="qms-heading">{t("retention.title")}</h2>
      <p className="qms-muted">{t("retention.intro")}</p>

      {policies.error !== null && <ErrorAlert>{describeError(t, policies.error)}</ErrorAlert>}
      {policies.items && policies.items.length > 0 && (
        <ul className="qms-list">
          {RETENTION_DATA_CLASSES.filter((dataClass) => policies.items!.some((p) => p.data_class === dataClass)).map((dataClass) => (
            <PolicyRow
              key={dataClass}
              policy={policies.items!.find((p) => p.data_class === dataClass)!}
              onSaved={policies.reload}
            />
          ))}
        </ul>
      )}
    </Card>
  );
}

function PolicyRow({ policy, onSaved }: { policy: RetentionPolicy; onSaved: () => void }) {
  const { t } = useI18n();
  const { client } = useApi();
  const id = useId();
  const { busy, error, run } = useSubmit();
  const [months, setMonths] = useState(String(policy.retention_months));
  const [mode, setMode] = useState<RetentionMode>(policy.mode);

  useEffect(() => {
    setMonths(String(policy.retention_months));
    setMode(policy.mode);
  }, [policy.retention_months, policy.mode]);

  const modeEditable = policy.data_class === "ticket_detail";
  const parsedMonths = Number.parseInt(months, 10);
  const dirty = parsedMonths !== policy.retention_months || mode !== policy.mode;

  async function save() {
    if (!client || !Number.isFinite(parsedMonths)) return;
    const ok = await run(() =>
      client.retention.updatePolicy(policy.data_class, { retention_months: parsedMonths, ...(modeEditable ? { mode } : {}) }),
    );
    if (ok) onSaved();
  }

  return (
    <li>
      <div className="qms-stack qms-grow">
        <strong>{t(`retention.dataClass.${policy.data_class}`)}</strong>
        <div className="qms-row">
          <TextField
            id={`${id}-months`}
            label={t("retention.retentionMonths")}
            type="number"
            min={1}
            max={1200}
            value={months}
            onChange={(event) => setMonths(event.target.value)}
          />
          {modeEditable && (
            <SelectField
              id={`${id}-mode`}
              label={t("retention.mode")}
              value={mode}
              onChange={(event) => setMode(event.target.value as RetentionMode)}
              options={MODES.map((value) => ({ value, label: t(`retention.mode.${value}`) }))}
            />
          )}
        </div>
        <span className="qms-muted">{t("retention.updatedAt", { date: new Date(policy.updated_at).toLocaleString() })}</span>
        <div className="qms-row">
          <Button type="button" onClick={save} disabled={busy || !dirty || !Number.isFinite(parsedMonths)}>
            {busy ? t("retention.saving") : t("retention.save")}
          </Button>
        </div>
        {error && <ErrorAlert>{error}</ErrorAlert>}
      </div>
    </li>
  );
}
