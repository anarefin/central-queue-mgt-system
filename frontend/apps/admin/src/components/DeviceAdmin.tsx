"use client";

import type {
  CreatePairingCodeInput,
  DeviceCommand,
  DeviceKind,
  DeviceView,
  DisplayAssignmentScope,
  DisplayColumn,
  DisplayLayout,
  PairingCodeResponse,
  Site,
  Zone,
} from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { Button, Card, ErrorAlert, SelectField, StatusBadge, TextField, type StatusKind } from "@qms/ui";
import { useId, useState, type FormEvent } from "react";
import { describeError, useList, useSubmit } from "../lib/admin-support";
import { useApi } from "../lib/runtime";

/** The configurable column set of FR-DSP-004, in display order; `token` and `counter` may never be turned off. */
const DISPLAY_COLUMNS: DisplayColumn[] = ["token", "counter", "service", "staff"];
const REQUIRED_COLUMNS = new Set<DisplayColumn>(["token", "counter"]);
const ASSIGNMENT_SCOPES: DisplayAssignmentScope[] = ["zone", "counters", "queues"];
/** The shipped layout set (ticket 28's `now_serving_table`, ticket 30's `split_media`, `single_counter`, `summary_board`), FR-DSP-003. */
const DISPLAY_LAYOUTS: DisplayLayout[] = ["now_serving_table", "split_media", "single_counter", "summary_board"];

const CONNECTIVITY_STATUS: Record<DeviceView["connectivity"], StatusKind> = {
  online: "up",
  stale: "not_configured",
  offline: "down",
};

/**
 * Device pairing and fleet management (FR-OPS-011, FR-OPS-041, FR-OPS-042, ticket 24): generate a pairing code for a
 * kiosk or display, watch the fleet's health, and revoke a device or push it a reload without touching it.
 */
export function DeviceAdmin() {
  const { client } = useApi();
  const sites = useList<Site>(client ? () => client.sites.list() : null, [client]);
  const devices = useList<DeviceView>(client ? () => client.devices.list() : null, [client]);

  return (
    <div className="qms-stack">
      <PairingCodeCard sites={sites.items ?? []} onPaired={devices.reload} />
      <DeviceListCard devices={devices} sites={sites.items ?? []} />
    </div>
  );
}

function PairingCodeCard({ sites, onPaired }: { sites: Site[]; onPaired: () => void }) {
  const { t } = useI18n();
  const { client } = useApi();
  const id = useId();
  const { busy, error, run } = useSubmit();
  const [kind, setKind] = useState<DeviceKind>("kiosk");
  const [siteId, setSiteId] = useState("");
  const [zoneId, setZoneId] = useState("");
  const [label, setLabel] = useState("");
  const [result, setResult] = useState<PairingCodeResponse | null>(null);

  const zones = useList<Zone>(client && siteId && kind === "display" ? () => client.sites.zones(siteId) : null, [client, siteId, kind]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setResult(null);
    const input: CreatePairingCodeInput = { kind, site_id: siteId, label, zone_id: kind === "display" ? zoneId || undefined : undefined };
    await run(async () => {
      const created = await client!.devices.createPairingCode(input);
      setResult(created);
      onPaired();
    });
  }

  return (
    <Card>
      <h2 className="qms-heading">{t("devices.pairingCode.title")}</h2>
      <form className="qms-stack" onSubmit={submit}>
        <SelectField
          id={`${id}-kind`}
          label={t("devices.fields.kind")}
          value={kind}
          onChange={(e) => setKind(e.target.value as DeviceKind)}
          options={[
            { value: "kiosk", label: t("devices.kind.kiosk") },
            { value: "display", label: t("devices.kind.display") },
          ]}
        />
        <SelectField
          id={`${id}-site`}
          label={t("devices.fields.site")}
          value={siteId}
          onChange={(e) => {
            setSiteId(e.target.value);
            setZoneId("");
          }}
          options={[{ value: "", label: "" }, ...sites.map((site) => ({ value: site.id, label: site.name }))]}
        />
        {kind === "display" && (
          <SelectField
            id={`${id}-zone`}
            label={t("devices.fields.zone")}
            value={zoneId}
            onChange={(e) => setZoneId(e.target.value)}
            options={[{ value: "", label: "" }, ...(zones.items ?? []).map((zone) => ({ value: zone.id, label: zone.name }))]}
          />
        )}
        <TextField id={`${id}-label`} label={t("devices.fields.label")} value={label} onChange={(e) => setLabel(e.target.value)} />
        {error && <ErrorAlert>{error}</ErrorAlert>}
        <div className="qms-row">
          <Button type="submit" disabled={busy}>
            {busy ? t("devices.pairingCode.generating") : t("devices.pairingCode.generate")}
          </Button>
        </div>
      </form>
      {result && (
        <>
          <p className="qms-muted">{t("devices.pairingCode.result", { code: result.code })}</p>
          <p className="qms-muted">{t("devices.pairingCode.expires", { time: new Date(result.expires_at).toLocaleString() })}</p>
        </>
      )}
    </Card>
  );
}

function DeviceListCard({ devices, sites }: { devices: ReturnType<typeof useList<DeviceView>>; sites: Site[] }) {
  const { t } = useI18n();

  const siteName = (id: string) => sites.find((site) => site.id === id)?.name ?? id;

  return (
    <Card>
      <h2 className="qms-heading">{t("devices.list.title")}</h2>
      {devices.error !== null && <ErrorAlert>{describeError(t, devices.error)}</ErrorAlert>}
      {devices.items?.length === 0 && <p className="qms-muted">{t("devices.list.empty")}</p>}
      {devices.items && devices.items.length > 0 && (
        <ul className="qms-list">
          {devices.items.map((device) => (
            <DeviceRow key={device.id} device={device} siteLabel={siteName(device.site_id)} onChanged={devices.reload} />
          ))}
        </ul>
      )}
    </Card>
  );
}

function DeviceRow({ device, siteLabel, onChanged }: { device: DeviceView; siteLabel: string; onChanged: () => void }) {
  const { t, formatDate, formatTime } = useI18n();
  const { client } = useApi();
  const { busy, error, run } = useSubmit();
  const [confirming, setConfirming] = useState(false);
  const [configuring, setConfiguring] = useState(false);
  const seen = device.last_heartbeat_at ? new Date(device.last_heartbeat_at) : null;

  async function push(command: DeviceCommand) {
    await run(() => client!.devices.command(device.id, command));
  }

  async function revoke() {
    if (await run(async () => client!.devices.revoke(device.id))) {
      setConfirming(false);
      onChanged();
    }
  }

  return (
    <li>
      <div className="qms-row">
        <span>
          {device.label} ({t(`devices.kind.${device.kind}`)}, {siteLabel})
        </span>
        <StatusBadge status={CONNECTIVITY_STATUS[device.connectivity]}>{t(`devices.connectivity.${device.connectivity}`)}</StatusBadge>
      </div>
      <p className="qms-muted">
        {seen ? t("devices.lastHeartbeat", { when: `${formatDate(seen)} ${formatTime(seen)}` }) : t("devices.lastHeartbeat.never")}
      </p>
      <p className="qms-muted">
        {device.last_app_version ? t("devices.version", { version: device.last_app_version }) : t("devices.version.unknown")}
      </p>
      <div className="qms-row">
        <Button variant="secondary" type="button" disabled={!device.active || busy} onClick={() => void push("reload")}>
          {t("devices.reload")}
        </Button>
        <Button variant="secondary" type="button" disabled={!device.active || busy} onClick={() => void push("config_changed")}>
          {t("devices.pushConfig")}
        </Button>
        {device.kind === "display" && (
          <Button variant="secondary" type="button" disabled={!device.active} onClick={() => setConfiguring((v) => !v)}>
            {t("devices.display.settings")}
          </Button>
        )}
        <Button variant="secondary" type="button" disabled={!device.active || busy} onClick={() => setConfirming(true)}>
          {t("devices.revoke")}
        </Button>
      </div>
      {configuring && <DisplayConfigForm device={device} onSaved={onChanged} />}
      {confirming && (
        <div className="qms-stack" role="group" aria-label={`${t("devices.revoke")} ${device.label}`}>
          <p>{t("devices.revoke.confirm", { label: device.label })}</p>
          <div className="qms-row">
            <Button type="button" disabled={busy} onClick={() => void revoke()}>
              {t("admin.action.confirmDeactivate")}
            </Button>
            <Button variant="secondary" type="button" onClick={() => setConfirming(false)}>
              {t("admin.action.cancel")}
            </Button>
          </div>
        </div>
      )}
      {error && <ErrorAlert>{error}</ErrorAlert>}
    </li>
  );
}

/**
 * A display's board configuration (ticket 28, FR-DSP-001..005): its column set, next-token depth, highlight period
 * and its assignment within its own zone to a whole Zone, specific Counters or specific queues (Services).
 */
function DisplayConfigForm({ device, onSaved }: { device: DeviceView; onSaved: () => void }) {
  const { t } = useI18n();
  const { client } = useApi();
  const id = useId();
  const { busy, error, run } = useSubmit();
  const [layout, setLayout] = useState<DisplayLayout>(device.layout);
  const [splitPercent, setSplitPercent] = useState(String(device.layout_config.split_percent ?? 60));
  const [singleCounterId, setSingleCounterId] = useState(device.layout_config.counter_id ?? "");
  const [languageCycleSeconds, setLanguageCycleSeconds] = useState(String(device.language_cycle_seconds));
  const [columns, setColumns] = useState<DisplayColumn[]>(device.columns);
  const [nextN, setNextN] = useState(String(device.next_n));
  const [highlightSeconds, setHighlightSeconds] = useState(String(device.highlight_seconds));
  const [scope, setScope] = useState<DisplayAssignmentScope>(device.assignment_scope);
  const [ids, setIds] = useState(device.assignment_ids.join(", "));
  const [saved, setSaved] = useState(false);

  function toggleColumn(column: DisplayColumn, checked: boolean) {
    if (REQUIRED_COLUMNS.has(column)) return; // FR-DSP-004's floor: token and counter are never optional
    setColumns((current) => (checked ? [...current.filter((c) => c !== column), column] : current.filter((c) => c !== column)));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaved(false);
    const ok = await run(async () => {
      const result = await client!.devices.updateDisplayConfig(device.id, {
        layout,
        layout_config:
          layout === "split_media"
            ? { split_percent: Number(splitPercent) }
            : layout === "single_counter"
              ? { counter_id: singleCounterId.trim() }
              : {},
        language_cycle_seconds: Number(languageCycleSeconds),
        columns,
        next_n: Number(nextN),
        highlight_seconds: Number(highlightSeconds),
        assignment: {
          scope,
          ids: scope === "zone" ? [] : ids.split(",").map((raw) => raw.trim()).filter((raw) => raw !== ""),
        },
      });
      setLayout(result.layout);
      setSplitPercent(String(result.layout_config.split_percent ?? 60));
      setSingleCounterId(result.layout_config.counter_id ?? "");
      setLanguageCycleSeconds(String(result.language_cycle_seconds));
      setColumns(result.columns);
      setNextN(String(result.next_n));
      setHighlightSeconds(String(result.highlight_seconds));
      setScope(result.assignment.scope);
      setIds(result.assignment.ids.join(", "));
      onSaved();
    });
    if (ok) setSaved(true);
  }

  return (
    <form className="qms-stack" onSubmit={submit} aria-label={t("devices.display.settings")}>
      <SelectField
        id={`${id}-layout`}
        label={t("devices.display.layout")}
        value={layout}
        onChange={(e) => setLayout(e.target.value as DisplayLayout)}
        options={DISPLAY_LAYOUTS.map((value) => ({ value, label: t(`devices.display.layout.${value}`) }))}
      />
      {layout === "split_media" && (
        <TextField
          id={`${id}-split-percent`}
          type="number"
          min={10}
          max={90}
          label={t("devices.display.splitPercent")}
          value={splitPercent}
          onChange={(e) => setSplitPercent(e.target.value)}
        />
      )}
      {layout === "single_counter" && (
        <TextField
          id={`${id}-single-counter-id`}
          label={t("devices.display.singleCounterId")}
          value={singleCounterId}
          onChange={(e) => setSingleCounterId(e.target.value)}
        />
      )}
      <TextField
        id={`${id}-language-cycle-seconds`}
        type="number"
        min={0}
        label={t("devices.display.languageCycleSeconds")}
        value={languageCycleSeconds}
        onChange={(e) => setLanguageCycleSeconds(e.target.value)}
      />
      <fieldset className="qms-stack">
        <legend className="qms-label">{t("devices.display.columnsLegend")}</legend>
        {DISPLAY_COLUMNS.map((column) => (
          <div className="qms-row" key={column}>
            <input
              type="checkbox"
              id={`${id}-column-${column}`}
              checked={columns.includes(column)}
              disabled={REQUIRED_COLUMNS.has(column)}
              onChange={(e) => toggleColumn(column, e.target.checked)}
            />
            <label htmlFor={`${id}-column-${column}`}>{t(`devices.display.columns.${column}`)}</label>
          </div>
        ))}
      </fieldset>
      <TextField
        id={`${id}-next-n`}
        label={t("devices.display.nextN")}
        type="number"
        min={1}
        value={nextN}
        onChange={(e) => setNextN(e.target.value)}
      />
      <TextField
        id={`${id}-highlight-seconds`}
        label={t("devices.display.highlightSeconds")}
        type="number"
        min={1}
        value={highlightSeconds}
        onChange={(e) => setHighlightSeconds(e.target.value)}
      />
      <SelectField
        id={`${id}-assignment-scope`}
        label={t("devices.display.assignmentScope")}
        value={scope}
        onChange={(e) => setScope(e.target.value as DisplayAssignmentScope)}
        options={ASSIGNMENT_SCOPES.map((value) => ({ value, label: t(`devices.display.assignmentScope.${value}`) }))}
      />
      {scope !== "zone" && (
        <TextField
          id={`${id}-assignment-ids`}
          label={t(scope === "counters" ? "devices.display.counterIds" : "devices.display.queueIds")}
          value={ids}
          onChange={(e) => setIds(e.target.value)}
        />
      )}
      {error && <ErrorAlert>{error}</ErrorAlert>}
      {saved && !error && <p className="qms-muted">{t("devices.display.saved")}</p>}
      <div className="qms-row">
        <Button type="submit" disabled={busy}>
          {busy ? t("admin.action.saving") : t("devices.display.save")}
        </Button>
      </div>
    </form>
  );
}
