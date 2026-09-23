"use client";

import type { VisitorImportMapping, VisitorImportReport } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, TextField } from "@qms/ui";
import { useEffect, useId, useState, type ChangeEvent, type FormEvent } from "react";
import { describeError, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

/**
 * Visitor master-data CSV import (SRS §22.2, FR-INT-010, FR-INT-011): an admin sets the column mapping once — which
 * CSV header feeds `external_code`, `name`, `phone`, `email` and `category` — then either uploads a file here or
 * lets the scheduled folder pickup (`qms.visitor.import.pickup-dir`) run the same mapping unattended. Either way,
 * every past run's validation report stays visible below, since a scheduled run has nobody present to see it live.
 */
export function VisitorImportAdmin() {
  const { t } = useI18n();
  const { client } = useApi();
  const runs = useList<VisitorImportReport>(client ? () => client.visitorImport.runs() : null, [client]);

  return (
    <div className="flex flex-col gap-4">
      <p className="text-fg-muted">{t("visitorImport.intro")}</p>
      <MappingCard />
      <UploadCard onImported={runs.reload} />
      <Card>
        <h2 className="font-semibold text-fg">{t("visitorImport.runs.title")}</h2>
        {runs.error !== null && <ErrorAlert>{describeError(t, runs.error)}</ErrorAlert>}
        {runs.items?.length === 0 && <p className="text-fg-muted">{t("visitorImport.runs.none")}</p>}
        {runs.items && runs.items.length > 0 && (
          <ul className="m-0 list-none p-0 flex flex-col divide-y divide-border [&>li]:flex [&>li]:flex-wrap [&>li]:items-center [&>li]:justify-between [&>li]:gap-2 [&>li]:py-2.5">
            {runs.items.map((run) => (
              <li key={run.id}>
                <RunSummary run={run} />
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}

function MappingCard() {
  const { t } = useI18n();
  const id = useId();
  const { client } = useApi();

  const [value, setValue] = useState<VisitorImportMapping | null>(null);
  const [loadError, setLoadError] = useState<unknown>(null);
  const { busy, error, run } = useSubmit();
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client.visitorImport.mapping().then(
      (result) => !cancelled && setValue(result),
      (cause: unknown) => !cancelled && setLoadError(cause),
    );
    return () => {
      cancelled = true;
    };
  }, [client]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!value) return;
    setSaved(false);
    const ok = await run(() => client!.visitorImport.setMapping(value));
    if (ok) setSaved(true);
  }

  function field(key: keyof VisitorImportMapping) {
    return value?.[key] ?? "";
  }

  function setField(key: keyof VisitorImportMapping, raw: string) {
    setValue((current) => (current ? { ...current, [key]: raw.trim() === "" ? null : raw } : current));
  }

  return (
    <Card>
      <h2 className="font-semibold text-fg">{t("visitorImport.mapping.title")}</h2>
      <p className="text-fg-muted">{t("visitorImport.mapping.intro")}</p>
      {loadError !== null && <ErrorAlert>{describeError(t, loadError)}</ErrorAlert>}
      {value && (
        <form className="flex flex-col gap-4" onSubmit={submit}>
          <TextField
            id={`${id}-external-code`}
            label={t("visitorImport.mapping.externalCodeColumn")}
            value={field("external_code_column") ?? ""}
            onChange={(e) => setField("external_code_column", e.target.value)}
            required
          />
          <TextField
            id={`${id}-name`}
            label={t("visitorImport.mapping.nameColumn")}
            value={field("name_column") ?? ""}
            onChange={(e) => setField("name_column", e.target.value)}
            required
          />
          <TextField
            id={`${id}-phone`}
            label={t("visitorImport.mapping.phoneColumn")}
            value={field("phone_column") ?? ""}
            onChange={(e) => setField("phone_column", e.target.value)}
          />
          <TextField
            id={`${id}-email`}
            label={t("visitorImport.mapping.emailColumn")}
            value={field("email_column") ?? ""}
            onChange={(e) => setField("email_column", e.target.value)}
          />
          <TextField
            id={`${id}-category`}
            label={t("visitorImport.mapping.categoryColumn")}
            value={field("category_column") ?? ""}
            onChange={(e) => setField("category_column", e.target.value)}
          />
          {error && <ErrorAlert>{error}</ErrorAlert>}
          {saved && !error && <p className="text-fg-muted">{t("visitorImport.mapping.saved")}</p>}
          <div className="flex flex-wrap items-center justify-between gap-3">
            <Button type="submit" disabled={busy}>
              {busy ? t("admin.action.saving") : t("visitorImport.mapping.save")}
            </Button>
          </div>
        </form>
      )}
    </Card>
  );
}

function UploadCard({ onImported }: { onImported: () => void }) {
  const { t } = useI18n();
  const { client } = useApi();
  const { busy, error, run, clearError } = useSubmit();
  const [filename, setFilename] = useState<string | null>(null);
  const [content, setContent] = useState<string | null>(null);
  const [report, setReport] = useState<VisitorImportReport | null>(null);

  async function pickFile(event: ChangeEvent<HTMLInputElement>) {
    clearError();
    setReport(null);
    const file = event.target.files?.[0];
    if (!file) {
      setFilename(null);
      setContent(null);
      return;
    }
    setFilename(file.name);
    setContent(await file.text());
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!filename || content === null) return;
    setReport(null);
    const ok = await run(async () => {
      const result = await client!.visitorImport.upload({ filename, content });
      setReport(result);
      onImported();
    });
    if (ok) {
      setFilename(null);
      setContent(null);
    }
  }

  return (
    <Card>
      <h2 className="font-semibold text-fg">{t("visitorImport.upload.title")}</h2>
      <p className="text-fg-muted">{t("visitorImport.upload.intro")}</p>
      <form className="flex flex-col gap-4" onSubmit={submit}>
        <div>
          <label className="block mb-1 font-medium text-fg text-sm" htmlFor="visitor-import-file">
            {t("visitorImport.upload.file")}
          </label>
          <input className="w-full rounded-md border border-border bg-surface px-3 py-2 text-fg focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary" id="visitor-import-file" type="file" accept=".csv,text/csv" onChange={pickFile} />
        </div>
        {error && <ErrorAlert>{error}</ErrorAlert>}
        <div className="flex flex-wrap items-center justify-between gap-3">
          <Button type="submit" disabled={busy || !filename}>
            {busy ? t("visitorImport.upload.importing") : t("visitorImport.upload.import")}
          </Button>
        </div>
      </form>
      {report && <ReportCard title={t("visitorImport.upload.result")} report={report} />}
    </Card>
  );
}

function ReportCard({ title, report }: { title: string; report: VisitorImportReport }) {
  const { t } = useI18n();
  return (
    <Card>
      <h2 className="font-semibold text-fg">{title}</h2>
      <p>
        {t("visitorImport.report.counts", {
          total: report.total_rows,
          inserted: report.inserted_count,
          updated: report.updated_count,
          failed: report.failed_count,
        })}
      </p>
      {report.errors.length > 0 && (
        <table className="w-full border-collapse text-sm [font-variant-numeric:tabular-nums] [&_th]:border-b [&_th]:border-border [&_th]:px-2.5 [&_th]:py-1.5 [&_th]:text-start [&_td]:border-b [&_td]:border-border [&_td]:px-2.5 [&_td]:py-1.5 [&_td]:text-start">
          <thead>
            <tr>
              <th>{t("visitorImport.report.line")}</th>
              <th>{t("visitorImport.report.field")}</th>
              <th>{t("visitorImport.report.reason")}</th>
            </tr>
          </thead>
          <tbody>
            {report.errors.map((e, i) => (
              <tr key={i}>
                <td>{e.line}</td>
                <td>{e.field}</td>
                <td>{t(`visitorImport.report.code.${e.code}`)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  );
}

function RunSummary({ run }: { run: VisitorImportReport }) {
  const { t } = useI18n();
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <strong>{run.filename ?? t("visitorImport.runs.noFilename")}</strong>
        <span className="text-fg-muted">{t(`visitorImport.runs.source.${run.source}`)}</span>
        <span className="text-fg-muted">{new Date(run.started_at).toLocaleString()}</span>
      </div>
      <p className="text-fg-muted">
        {t("visitorImport.report.counts", {
          total: run.total_rows,
          inserted: run.inserted_count,
          updated: run.updated_count,
          failed: run.failed_count,
        })}
      </p>
    </div>
  );
}
