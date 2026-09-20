"use client";

import { PRINT_FIELDS, type OrgBranding, type PrintField, type PrintTemplate } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, QrCode, TextField } from "@qms/ui";
import { useEffect, useId, useState, type FormEvent } from "react";
import { describeError, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

/**
 * Branding and the printed-token template (ticket 27, SRS §7.5): an Org Admin sets the organisation's logo,
 * primary colour and name once (FR-CFG-030), edits the printed token's layout from the fixed field set
 * (FR-CFG-031), and previews and test-prints it here without issuing a real Ticket (FR-CFG-032). Kiosk, display and
 * the printed token itself read the same two settings through their own bootstrap (ticket 24) — this page is the
 * only place either is ever written.
 */
export function BrandingAdmin() {
  const { t } = useI18n();
  const { client } = useApi();
  const [branding, setBranding] = useState<OrgBranding | null>(null);
  const [template, setTemplate] = useState<PrintTemplate | null>(null);
  const [loadError, setLoadError] = useState<unknown>(null);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    Promise.all([client.branding.get(), client.branding.template()]).then(
      ([b, tpl]) => {
        if (!cancelled) {
          setBranding(b);
          setTemplate(tpl);
        }
      },
      (cause: unknown) => !cancelled && setLoadError(cause),
    );
    return () => {
      cancelled = true;
    };
  }, [client]);

  return (
    <div className="qms-stack">
      <p className="qms-muted">{t("branding.intro")}</p>
      {loadError !== null && <ErrorAlert>{describeError(t, loadError)}</ErrorAlert>}
      {branding && <BrandingCard value={branding} onSaved={setBranding} />}
      {template && <TemplateCard value={template} onSaved={setTemplate} />}
      {branding && template && <PreviewCard branding={branding} template={template} />}
    </div>
  );
}

function BrandingCard({ value, onSaved }: { value: OrgBranding; onSaved: (b: OrgBranding) => void }) {
  const { t } = useI18n();
  const { client } = useApi();
  const id = useId();
  const [orgName, setOrgName] = useState(value.org_name);
  const [primaryColor, setPrimaryColor] = useState(value.primary_color);
  const [logoUrl, setLogoUrl] = useState(value.logo_url ?? "");
  const { busy, error, run } = useSubmit();
  const [saved, setSaved] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaved(false);
    const ok = await run(async () => {
      const result = await client!.branding.update({ org_name: orgName, primary_color: primaryColor, logo_url: logoUrl.trim() === "" ? null : logoUrl });
      onSaved(result);
      setOrgName(result.org_name);
      setPrimaryColor(result.primary_color);
      setLogoUrl(result.logo_url ?? "");
    });
    if (ok) setSaved(true);
  }

  return (
    <Card>
      <h2 className="qms-heading">{t("branding.section.orgBranding")}</h2>
      <form className="qms-stack" onSubmit={submit}>
        <TextField id={`${id}-org-name`} label={t("branding.orgNameLabel")} value={orgName} onChange={(e) => setOrgName(e.target.value)} required />
        <TextField
          id={`${id}-primary-color`}
          label={t("branding.primaryColorLabel")}
          type="color"
          value={primaryColor}
          onChange={(e) => setPrimaryColor(e.target.value)}
        />
        <TextField id={`${id}-logo-url`} label={t("branding.logoUrlLabel")} value={logoUrl} onChange={(e) => setLogoUrl(e.target.value)} />
        {error && <ErrorAlert>{error}</ErrorAlert>}
        {saved && !error && <p className="qms-muted">{t("branding.brandingSaved")}</p>}
        <div className="qms-row">
          <Button type="submit" disabled={busy}>
            {busy ? t("admin.action.saving") : t("branding.saveBranding")}
          </Button>
        </div>
      </form>
    </Card>
  );
}

function TemplateCard({ value, onSaved }: { value: PrintTemplate; onSaved: (t: PrintTemplate) => void }) {
  const { t } = useI18n();
  const { client } = useApi();
  const id = useId();
  const [fields, setFields] = useState<PrintField[]>(value.fields);
  const [noticeLine, setNoticeLine] = useState(value.notice_line ?? "");
  const { busy, error, run } = useSubmit();
  const [saved, setSaved] = useState(false);

  function toggle(field: PrintField, checked: boolean) {
    setFields((current) => (checked ? [...current.filter((f) => f !== field), field] : current.filter((f) => f !== field)));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaved(false);
    const ok = await run(async () => {
      const result = await client!.branding.updateTemplate({ fields, notice_line: noticeLine.trim() === "" ? null : noticeLine });
      onSaved(result);
      setFields(result.fields);
      setNoticeLine(result.notice_line ?? "");
    });
    if (ok) setSaved(true);
  }

  return (
    <Card>
      <h2 className="qms-heading">{t("branding.section.template")}</h2>
      <p className="qms-muted">{t("branding.templateIntro")}</p>
      <form className="qms-stack" onSubmit={submit}>
        <fieldset className="qms-stack">
          <legend className="qms-label">{t("branding.fieldsLegend")}</legend>
          {PRINT_FIELDS.map((field) => (
            <div className="qms-row" key={field}>
              <input
                type="checkbox"
                id={`${id}-field-${field}`}
                checked={fields.includes(field)}
                onChange={(e) => toggle(field, e.target.checked)}
              />
              <label htmlFor={`${id}-field-${field}`}>{t(`branding.fields.${field}`)}</label>
            </div>
          ))}
        </fieldset>
        <TextField id={`${id}-notice-line`} label={t("branding.noticeLineLabel")} value={noticeLine} onChange={(e) => setNoticeLine(e.target.value)} />
        {error && <ErrorAlert>{error}</ErrorAlert>}
        {saved && !error && <p className="qms-muted">{t("branding.templateSaved")}</p>}
        <div className="qms-row">
          <Button type="submit" disabled={busy}>
            {busy ? t("admin.action.saving") : t("branding.saveTemplate")}
          </Button>
        </div>
      </form>
    </Card>
  );
}

/** Fixed sample data (FR-CFG-032): the preview and test print never call the ticket API and never issue a real Ticket. */
function sampleValue(t: (key: string) => string, field: PrintField): string {
  switch (field) {
    case "token_number":
      return "A-001";
    case "building":
      return t("branding.preview.sampleBuilding");
    case "floor":
      return t("branding.preview.sampleFloor");
    case "service_group":
      return t("branding.preview.sampleServiceGroup");
    case "service":
      return t("branding.preview.sampleService");
    case "visitor_code":
      return "V-1234";
    case "visitor_name":
      return t("branding.preview.sampleVisitorName");
    case "visitor_category":
      return t("branding.preview.sampleVisitorCategory");
    case "counter":
      return t("branding.preview.sampleCounter");
    case "issue_time":
      return new Date().toLocaleString();
    case "estimated_wait":
      return t("branding.preview.sampleEstimatedWait");
    default:
      return "";
  }
}

function PreviewCard({ branding, template }: { branding: OrgBranding; template: PrintTemplate }) {
  const { t } = useI18n();
  const [printError, setPrintError] = useState(false);

  function testPrint() {
    setPrintError(false);
    if (typeof window === "undefined" || typeof window.print !== "function") {
      setPrintError(true);
      return;
    }
    window.print();
  }

  return (
    <Card>
      <h2 className="qms-heading">{t("branding.section.preview")}</h2>
      <p className="qms-muted">{t("branding.previewIntro")}</p>
      <div className="qms-print-slip-preview" aria-label={t("branding.preview.regionLabel")}>
        <SlipContent branding={branding} template={template} t={t} />
      </div>
      {printError && <ErrorAlert>{t("branding.preview.printUnavailable")}</ErrorAlert>}
      <div className="qms-row">
        <Button type="button" onClick={testPrint}>
          {t("branding.testPrint")}
        </Button>
      </div>
      <div className="qms-print-slip" aria-hidden="true">
        <SlipContent branding={branding} template={template} t={t} />
      </div>
    </Card>
  );
}

function SlipContent({ branding, template, t }: { branding: OrgBranding; template: PrintTemplate; t: (key: string, params?: Record<string, string | number>) => string }) {
  const fields = template.fields;
  return (
    <div style={{ ["--qms-print-accent" as string]: branding.primary_color }}>
      <div className="qms-print-slip-accent" />
      {branding.logo_url && <img className="qms-print-slip-logo" src={branding.logo_url} alt={t("branding.logoAlt", { org: branding.org_name })} />}
      <p>
        <strong>{branding.org_name}</strong>
      </p>
      {fields.map((field) =>
        field === "qr_code" ? (
          <QrCode key={field} value="https://example.org/visitor/preview" size={96} label={t("branding.preview.qrLabel")} />
        ) : field === "notice_line" ? (
          template.notice_line ? <p key={field}>{template.notice_line}</p> : null
        ) : (
          <p key={field}>
            {t(`branding.fields.${field}`)}: {sampleValue(t, field)}
          </p>
        ),
      )}
    </div>
  );
}
