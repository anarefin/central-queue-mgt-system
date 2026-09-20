"use client";

import type { DisplayNextGroup, DisplayServingEntry, DisplayState } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { ErrorAlert } from "@qms/ui";
import { useEffect, useRef, useState } from "react";
import { useApi } from "../lib/runtime";

/** FR-DSP-011: a discreet stale indicator once nothing has updated for this long, rather than showing wrong data silently. */
const STALE_AFTER_MS = 30_000;
/** How often the whole zone is re-fetched in full, on top of live events, so a column an event does not carry (a
 * Service's name, a staff member's name) and the next-token strip never drift for longer than this (ticket 28). */
const REFRESH_INTERVAL_MS = 20_000;
/** A newly called or re-announced token pulses; the tick that expires that highlight and the stale check share one timer. */
const TICK_MS = 1_000;

const CALL_EVENTS = new Set(["ticket.called", "ticket.reannounced"]);

/** {@code now_serving_table} (FR-DSP-003): serving token / counter / service, plus a next-token strip per queue. */
export function NowServingBoard({ deviceId }: { deviceId: string }) {
  const { t, language } = useI18n();
  const { client, realtime } = useApi();
  const [state, setState] = useState<DisplayState | null>(null);
  const [error, setError] = useState(false);
  const [lastUpdateAt, setLastUpdateAt] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const highlightUntil = useRef<Record<string, number>>({});
  const stateRef = useRef<DisplayState | null>(null);
  stateRef.current = state;

  const load = useRef<() => void>(() => undefined);
  load.current = () => {
    client?.devices.displayState(deviceId).then(
      (result) => {
        setState(result);
        setLastUpdateAt(Date.now());
        setError(false);
      },
      () => setError(true),
    );
  };

  // FR-DSP-012: resume with no login -- the first load, and full periodic re-fetches after that (see REFRESH_INTERVAL_MS above).
  useEffect(() => {
    if (!client) return;
    load.current();
    const interval = setInterval(() => load.current(), REFRESH_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [client, deviceId]);

  // FR-DSP-010, FR-DSP-011: live updates on the zone's own topic, with automatic reconnect and the FR-QUE-084 polling
  // fallback both already handled by the realtime client; only re-subscribes when the zone itself changes.
  const zoneId = state?.zone.id ?? null;
  useEffect(() => {
    if (!realtime || !zoneId) return;
    return realtime.subscribe(`zone:${zoneId}`, (update) => {
      if (update.kind === "snapshot") {
        const data = update.data as unknown as { serving: DisplayServingEntry[]; next: DisplayNextGroup[] };
        setState((prev) => (prev ? { ...prev, serving: data.serving, next: data.next } : prev));
        setLastUpdateAt(Date.now());
      } else if (update.kind === "event") {
        const data = update.data as Record<string, unknown>;
        const counterId = data.counter_id as string | undefined;
        if (counterId) {
          setState((prev) => prev && patchCounter(prev, counterId, data));
          if (CALL_EVENTS.has(update.type)) {
            const seconds = stateRef.current?.highlight_seconds ?? 10;
            highlightUntil.current = { ...highlightUntil.current, [counterId]: Date.now() + seconds * 1000 };
          }
        }
        setLastUpdateAt(Date.now());
      }
    });
  }, [realtime, zoneId]);

  useEffect(() => {
    const interval = setInterval(() => setNow(Date.now()), TICK_MS);
    return () => clearInterval(interval);
  }, []);

  if (error && !state) return <ErrorAlert>{t("nowServing.loadError")}</ErrorAlert>;
  if (!state) return <p className="qms-muted">{t("common.loading")}</p>;

  const stale = lastUpdateAt !== null && now - lastUpdateAt > STALE_AFTER_MS;
  const serving = filterServing(state);
  const next = filterNext(state);

  return (
    <div className="qms-now-serving">
      {stale && (
        <span className="qms-now-serving-stale" role="status" aria-live="polite">
          {t("nowServing.stale")}
        </span>
      )}
      <table className="qms-now-serving-table">
        <thead>
          <tr>
            {state.columns.includes("token") && <th>{t("nowServing.columns.token")}</th>}
            {state.columns.includes("counter") && <th>{t("nowServing.columns.counter")}</th>}
            {state.columns.includes("service") && <th>{t("nowServing.columns.service")}</th>}
            {state.columns.includes("staff") && <th>{t("nowServing.columns.staff")}</th>}
          </tr>
        </thead>
        <tbody>
          {serving.map((row) => {
            const highlighted = (highlightUntil.current[row.counter_id] ?? 0) > now;
            return (
              <tr key={row.counter_id} className={highlighted ? "qms-now-serving-row--highlight" : undefined}>
                {state.columns.includes("token") && (
                  <td className="qms-now-serving-token">{row.token_number ?? "—"}</td>
                )}
                {state.columns.includes("counter") && <td>{row.counter_label}</td>}
                {state.columns.includes("service") && <td>{localised(row.service_names, language) ?? "—"}</td>}
                {state.columns.includes("staff") && <td>{row.staff_name ?? "—"}</td>}
              </tr>
            );
          })}
        </tbody>
      </table>

      {next.length > 0 && (
        <div className="qms-now-serving-next" aria-label={t("nowServing.nextLabel")}>
          {next.map((group) => (
            <div key={group.service_id} className="qms-now-serving-next-group">
              <span className="qms-muted">{localised(group.service_names, language)}</span>
              <ol>
                {group.tokens.slice(0, state.next_n).map((token) => (
                  <li key={token.token_number}>{token.token_number}</li>
                ))}
              </ol>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/** Applies a `zone:` event's ticket fields to the one Counter row it names; everything else is left as it was. */
function patchCounter(state: DisplayState, counterId: string, data: Record<string, unknown>): DisplayState {
  return {
    ...state,
    serving: state.serving.map((row) =>
      row.counter_id === counterId
        ? {
            ...row,
            token_number: (data.token_number as string | undefined) ?? null,
            state: (data.state as DisplayServingEntry["state"]) ?? null,
            service_id: (data.service_id as string | undefined) ?? row.service_id,
          }
        : row,
    ),
  };
}

/**
 * A display assigned to fewer Counters than its whole zone (FR-DSP-002) narrows the zone's live feed down to its own
 * assignment; `zone`-scoped displays (this build's common case, and the only one exercised end to end by the backend
 * IT suite) show everything the zone publishes.
 */
function filterServing(state: DisplayState): DisplayServingEntry[] {
  if (state.assignment.scope === "counters") {
    const ids = new Set(state.assignment.ids);
    return state.serving.filter((row) => ids.has(row.counter_id));
  }
  if (state.assignment.scope === "queues") {
    const ids = new Set(state.assignment.ids);
    return state.serving.filter((row) => row.service_id !== null && ids.has(row.service_id));
  }
  return state.serving;
}

function filterNext(state: DisplayState): DisplayNextGroup[] {
  if (state.assignment.scope === "queues") {
    const ids = new Set(state.assignment.ids);
    return state.next.filter((group) => ids.has(group.service_id));
  }
  return state.next;
}

function localised(names: Record<string, string>, language: string): string | null {
  return names[language] ?? names.en ?? Object.values(names)[0] ?? null;
}
