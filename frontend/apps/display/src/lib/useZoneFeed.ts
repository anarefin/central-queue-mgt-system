"use client";

import type { DisplayNextGroup, DisplayServingEntry, DisplayState } from "@qms/api-client";
import { useEffect, useMemo, useRef, useState } from "react";
import { AnnouncementQueue, type Speaker } from "./announcementQueue";
import { useApi } from "./runtime";
import { createSpeaker } from "./speaker";
import { enqueueAnnouncement, zoneAudioConfig } from "./zoneAnnouncements";
import { fillCalledRows } from "./displayFilters";

/** FR-DSP-011: a discreet stale indicator once nothing has updated for this long, rather than showing wrong data silently. */
const STALE_AFTER_MS = 30_000;
/** How often the whole zone is re-fetched in full, on top of live events (ticket 28). */
const REFRESH_INTERVAL_MS = 20_000;
/** A newly called or re-announced token pulses; the tick that expires that highlight and the stale check share one timer. */
const TICK_MS = 1_000;

const CALL_EVENTS = new Set(["ticket.called", "ticket.reannounced"]);

export interface ZoneFeed {
  state: DisplayState | null;
  error: boolean;
  stale: boolean;
  now: number;
  /** Counter id -> the timestamp its highlight expires at (FR-DSP-007). */
  highlightUntil: Record<string, number>;
}

/**
 * The "load, then live-patch" feed every FR-DSP-003 layout beyond `now_serving_table` needs (ticket 30): the same
 * `display-state` resume read and `zone:` realtime subscription {@code NowServingBoard} uses (ticket 28). Calls are
 * announced here too, through ticket 29's queue, for every layout except `now_serving_table`, whose own board already
 * announces them, so a TV that also shows a notice panel still speaks each call. `split_media` and `single_counter` render this same live serving/highlight state;
 * `summary_board` only needs the periodic re-fetch, since its own waiting counts and estimates are not part of the
 * `zone:` topic's event shape.
 */
export function useZoneFeed(deviceId: string, speaker?: Speaker): ZoneFeed {
  const { client, realtime } = useApi();
  const [state, setState] = useState<DisplayState | null>(null);
  const [error, setError] = useState(false);
  const [lastUpdateAt, setLastUpdateAt] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const highlightUntil = useRef<Record<string, number>>({});
  const stateRef = useRef<DisplayState | null>(null);
  stateRef.current = state;
  const announcer = useMemo(() => new AnnouncementQueue(speaker ?? createSpeaker(), () => zoneAudioConfig(stateRef.current)), [speaker]);

  // Only rows still showing the token the fresh read shows take its names: a read that raced a newer call never
  // rolls that call back.
  const fillCalled = useRef<() => void>(() => undefined);
  fillCalled.current = () => {
    client?.devices.displayState(deviceId).then(
      (fresh) => setState((prev) => prev && fillCalledRows(prev, fresh)),
      () => undefined,
    );
  };

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

  useEffect(() => {
    if (!client) return;
    load.current();
    const interval = setInterval(() => load.current(), REFRESH_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [client, deviceId]);

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
            if (stateRef.current?.layout !== "now_serving_table") enqueueAnnouncement(announcer, stateRef.current, counterId, data);
            // The event carries the token, not the Service or staff names; re-read now rather than show a called row
            // with blanks until the next periodic refresh.
            fillCalled.current();
          }
        }
        setLastUpdateAt(Date.now());
      }
    });
  }, [realtime, zoneId, announcer]);

  useEffect(() => {
    const interval = setInterval(() => setNow(Date.now()), TICK_MS);
    return () => clearInterval(interval);
  }, []);

  const stale = lastUpdateAt !== null && now - lastUpdateAt > STALE_AFTER_MS;
  return { state, error, stale, now, highlightUntil: highlightUntil.current };
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
