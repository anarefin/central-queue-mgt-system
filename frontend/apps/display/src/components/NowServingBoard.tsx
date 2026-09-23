"use client";

import type { DisplayNextGroup, DisplayServingEntry, DisplayState } from "@qms/api-client";
import { useI18n } from "@qms/i18n/react";
import { ErrorAlert } from "@qms/ui";
import { useEffect, useMemo, useRef, useState } from "react";
import { AnnouncementQueue, type Speaker, type ZoneAudioConfig } from "../lib/announcementQueue";
import { filterNext, filterServing, localised } from "../lib/displayFilters";
import { createSpeaker } from "../lib/speaker";
import { useApi } from "../lib/runtime";

/** FR-DSP-011: a discreet stale indicator once nothing has updated for this long, rather than showing wrong data silently. */
const STALE_AFTER_MS = 30_000;
/** How often the whole zone is re-fetched in full, on top of live events, so a column an event does not carry (a
 * Service's name, a staff member's name) and the next-token strip never drift for longer than this (ticket 28). */
const REFRESH_INTERVAL_MS = 20_000;
/** A newly called or re-announced token pulses; the tick that expires that highlight and the stale check share one timer. */
const TICK_MS = 1_000;

const CALL_EVENTS = new Set(["ticket.called", "ticket.reannounced"]);

/** {@code now_serving_table} (FR-DSP-003): serving token / counter / service, plus a next-token strip per queue. `speaker` is injectable for tests; a real display uses the browser's TTS/clip speaker (ticket 29). */
export function NowServingBoard({ deviceId, speaker }: { deviceId: string; speaker?: Speaker }) {
  const { t, language, formatToken } = useI18n();
  const { client, realtime } = useApi();
  const [state, setState] = useState<DisplayState | null>(null);
  const [error, setError] = useState(false);
  const [lastUpdateAt, setLastUpdateAt] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const highlightUntil = useRef<Record<string, number>>({});
  const stateRef = useRef<DisplayState | null>(null);
  stateRef.current = state;

  // Ticket 29 (FR-DSP-020..028): one queue per mounted board, so calls never overlap; it reads the zone's live audio
  // settings off `stateRef` at announce time, not at enqueue time, so a config change mid-queue takes effect at once.
  const announcer = useMemo(() => new AnnouncementQueue(speaker ?? createSpeaker(), () => zoneAudioConfig(stateRef.current)), [speaker]);

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
            enqueueAnnouncement(announcer, stateRef.current, counterId, data);
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
  if (!state) return <p className="text-fg-muted">{t("common.loading")}</p>;

  const stale = lastUpdateAt !== null && now - lastUpdateAt > STALE_AFTER_MS;
  const serving = filterServing(state);
  const next = filterNext(state);

  return (
    <div className="flex flex-col gap-6 p-6">
      {stale && (
        <span
          className="self-start rounded-full border border-warn bg-warn-subtle px-3 py-1 text-sm text-warn"
          role="status"
          aria-live="polite"
        >
          {t("nowServing.stale")}
        </span>
      )}
      <table className="w-full border-collapse text-2xl">
        <thead>
          <tr>
            {state.columns.includes("token") && <th className="border-b border-border px-4 py-3 text-start">{t("nowServing.columns.token")}</th>}
            {state.columns.includes("counter") && <th className="border-b border-border px-4 py-3 text-start">{t("nowServing.columns.counter")}</th>}
            {state.columns.includes("service") && <th className="border-b border-border px-4 py-3 text-start">{t("nowServing.columns.service")}</th>}
            {state.columns.includes("staff") && <th className="border-b border-border px-4 py-3 text-start">{t("nowServing.columns.staff")}</th>}
          </tr>
        </thead>
        <tbody>
          {serving.map((row) => {
            const highlighted = (highlightUntil.current[row.counter_id] ?? 0) > now;
            return (
              <tr key={row.counter_id} className={highlighted ? "bg-primary/15 motion-safe:animate-highlight-pulse" : undefined}>
                {state.columns.includes("token") && (
                  <td className="border-b border-border px-4 py-3 text-[60px] font-bold tabular-nums">{formatToken(row.token_number ?? "—")}</td>
                )}
                {state.columns.includes("counter") && <td className="border-b border-border px-4 py-3">{row.counter_label}</td>}
                {state.columns.includes("service") && <td className="border-b border-border px-4 py-3">{localised(row.service_names, language) ?? "—"}</td>}
                {state.columns.includes("staff") && <td className="border-b border-border px-4 py-3">{row.staff_name ?? "—"}</td>}
              </tr>
            );
          })}
        </tbody>
      </table>

      {next.length > 0 && (
        <div className="flex flex-wrap gap-6" aria-label={t("nowServing.nextLabel")}>
          {next.map((group) => (
            <div key={group.service_id} className="flex flex-col gap-2">
              <span className="text-fg-muted">{localised(group.service_names, language)}</span>
              <ol className="m-0 flex list-none gap-3 p-0 text-xl tabular-nums">
                {group.tokens.slice(0, state.next_n).map((token) => (
                  <li key={token.token_number}>{formatToken(token.token_number)}</li>
                ))}
              </ol>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * The zone's voice-announcement settings from its last loaded state, or a silent-audio-but-no-quiet-period default
 * before anything has loaded (the board renders nothing to announce yet at that point regardless).
 */
function zoneAudioConfig(state: DisplayState | null): ZoneAudioConfig {
  const zone = state?.zone;
  return {
    chime: zone?.chime ?? "chime_standard",
    chimeVolume: zone?.chime_volume ?? 80,
    quietStart: zone?.quiet_start ?? null,
    quietEnd: zone?.quiet_end ?? null,
    announcementLanguages: zone?.announcement_languages ?? ["en"],
    maxAnnounceQueueDepth: zone?.max_announce_queue_depth ?? 5,
  };
}

/**
 * Builds and enqueues the announcement for a `ticket.called`/`ticket.reannounced` event (FR-DSP-020, FR-DSP-028).
 * The Counter's own last-loaded row supplies the fields the event itself does not carry (counter label, Service
 * names, its token prefix and spoken forms, its visitor-name flag); like `patchCounter`'s own fields, these settle
 * to the call's own Service within `REFRESH_INTERVAL_MS` of a Service change at that Counter (ticket 28's same
 * eventual-consistency window, see the ticket 29 traceability notes). Nothing is enqueued when the board has not
 * loaded that Counter yet, or the event carries no `announce_count` (never true for a real `ticket.called`/
 * `ticket.reannounced`, guarded here only so a malformed event cannot crash the board).
 */
function enqueueAnnouncement(announcer: AnnouncementQueue, state: DisplayState | null, counterId: string, data: Record<string, unknown>): void {
  const row = state?.serving.find((entry) => entry.counter_id === counterId);
  const tokenNumber = (data.token_number as string | undefined) ?? row?.token_number;
  const announceCount = data.announce_count as number | undefined;
  if (!state || !row || !tokenNumber || announceCount === undefined) return;
  announcer.enqueue({
    ticketId: data.ticket_id as string,
    announceCount,
    counterId,
    tokenNumber,
    tokenPrefix: row.token_prefix,
    tokenPrefixSpoken: row.token_prefix_spoken,
    counterLabel: row.counter_label,
    serviceNames: row.service_names,
    floorLabel: state.zone.floor_label,
    announceVisitorName: row.announce_visitor_name,
  });
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

