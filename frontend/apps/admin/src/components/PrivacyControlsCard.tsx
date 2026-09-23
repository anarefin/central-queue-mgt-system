"use client";

import type { VisitorExport, VisitorFieldConfig, VisitorFieldSurface } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, TextField } from "@qms/ui";
import { useId, useState } from "react";
import { describeError, useConfirmDialog, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

const SURFACES: VisitorFieldSurface[] = ["capture", "kiosk_confirmation"];

/**
 * Privacy controls (SRS §25.3-25.4, ticket 54): which optional visitor fields the walk-in registration capture step
 * and the kiosk confirmation screen show, and a single visitor's own data export or deletion (FR-SEC-031). Clinical
 * sensitivity is a per-Site flag, set from the Sites screen alongside a Site's other settings; the other §25.3
 * surfaces (printed token, public display, announcement, agent console, report export) each already have their own
 * admin screen from an earlier ticket.
 */
export function PrivacyControlsCard() {
  const { t } = useI18n();
  return (
    <div className="flex flex-col gap-4">
      <Card>
        <h2 className="font-semibold text-fg">{t("privacy.fieldConfig.title")}</h2>
        <p className="text-fg-muted">{t("privacy.fieldConfig.intro")}</p>
        {SURFACES.map((surface) => (
          <SurfaceSection key={surface} surface={surface} />
        ))}
      </Card>
      <Card>
        <VisitorDataSection />
      </Card>
    </div>
  );
}

function SurfaceSection({ surface }: { surface: VisitorFieldSurface }) {
  const { t } = useI18n();
  const { client } = useApi();
  const fields = useList<VisitorFieldConfig>(client ? () => client.privacy.fieldConfig(surface) : null, [client, surface]);

  return (
    <div className="flex flex-col gap-4">
      <h3>{t(`privacy.fieldConfig.surface.${surface}`)}</h3>
      {fields.error !== null && <ErrorAlert>{describeError(t, fields.error)}</ErrorAlert>}
      {fields.items && (
        <ul className="m-0 list-none p-0 flex flex-col divide-y divide-border [&>li]:flex [&>li]:flex-wrap [&>li]:items-center [&>li]:justify-between [&>li]:gap-2 [&>li]:py-2.5">
          {fields.items.map((field) => (
            <FieldRow key={field.field} config={field} onSaved={fields.reload} />
          ))}
        </ul>
      )}
    </div>
  );
}

function FieldRow({ config, onSaved }: { config: VisitorFieldConfig; onSaved: () => void }) {
  const { t } = useI18n();
  const { client } = useApi();
  const id = useId();
  const { busy, error, run } = useSubmit();

  async function toggle(visible: boolean) {
    if (!client) return;
    const ok = await run(() => client.privacy.setFieldConfig(config.surface, config.field, visible));
    if (ok) onSaved();
  }

  return (
    <li>
      <label htmlFor={id} className="flex flex-wrap items-center justify-between gap-3">
        <input id={id} type="checkbox" checked={config.visible} disabled={busy} onChange={(event) => toggle(event.target.checked)} />
        {t(`privacy.fieldConfig.field.${config.field}`)}
      </label>
      {error && <ErrorAlert>{error}</ErrorAlert>}
    </li>
  );
}

function VisitorDataSection() {
  const { t } = useI18n();
  const { client } = useApi();
  const id = useId();
  const { busy, error, run } = useSubmit();
  const [visitorId, setVisitorId] = useState("");
  const [exported, setExported] = useState<VisitorExport | null>(null);
  const [anonymizedAt, setAnonymizedAt] = useState<string | null>(null);
  const { ask, dialog } = useConfirmDialog();

  async function doExport() {
    if (!client || !visitorId.trim()) return;
    setAnonymizedAt(null);
    await run(async () => setExported(await client.privacy.exportVisitor(visitorId.trim())));
  }

  function doAnonymize() {
    if (!client || !visitorId.trim()) return;
    ask({
      title: t("privacy.visitorData.anonymize"),
      description: t("privacy.visitorData.confirmAnonymize"),
      danger: true,
      onConfirm: () => {
        void run(async () => {
          const result = await client.privacy.anonymizeVisitor(visitorId.trim());
          setAnonymizedAt(result.anonymized_at);
        }).then((ok) => ok && setExported(null));
      },
    });
  }

  return (
    <div className="flex flex-col gap-4">
      <h2 className="font-semibold text-fg">{t("privacy.visitorData.title")}</h2>
      <p className="text-fg-muted">{t("privacy.visitorData.intro")}</p>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <TextField id={`${id}-visitor`} label={t("privacy.visitorData.visitorId")} value={visitorId} onChange={(event) => setVisitorId(event.target.value)} />
        <Button type="button" onClick={doExport} disabled={busy || !visitorId.trim()}>
          {t("privacy.visitorData.export")}
        </Button>
        <Button type="button" variant="secondary" onClick={doAnonymize} disabled={busy || !visitorId.trim()}>
          {t("privacy.visitorData.anonymize")}
        </Button>
      </div>
      {dialog}
      {error && <ErrorAlert>{error}</ErrorAlert>}
      {anonymizedAt && <p className="text-fg-muted">{t("privacy.visitorData.anonymized", { date: new Date(anonymizedAt).toLocaleString() })}</p>}
      {exported && (
        <dl>
          <dt>{t("privacy.fieldConfig.field.name")}</dt>
          <dd>{exported.name ?? "—"}</dd>
          <dt>{t("privacy.fieldConfig.field.category")}</dt>
          <dd>{exported.category ?? "—"}</dd>
          <dt>{t("privacy.fieldConfig.field.phone")}</dt>
          <dd>{exported.phone ?? "—"}</dd>
          <dt>{t("privacy.fieldConfig.field.email")}</dt>
          <dd>{exported.email ?? "—"}</dd>
          <dt>{t("privacy.visitorData.tickets")}</dt>
          <dd>{exported.tickets.length}</dd>
        </dl>
      )}
    </div>
  );
}
