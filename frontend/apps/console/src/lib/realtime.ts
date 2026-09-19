"use client";

import type { RealtimeUpdate } from "@qms/realtime-client";
import { useEffect, useRef } from "react";
import { useApi } from "./runtime";

/**
 * Listens to realtime topics for as long as the component is mounted and the list does not change. The listener may
 * change on every render; the subscriptions do not (so a re-render never costs a new snapshot).
 */
export function useTopics(topics: readonly string[], listener: (update: RealtimeUpdate) => void): void {
  const { realtime } = useApi();
  const latest = useRef(listener);
  latest.current = listener;
  const key = [...new Set(topics)].sort().join("|");

  useEffect(() => {
    if (!realtime || key === "") return;
    const stops = key.split("|").map((topic) => realtime.subscribe(topic, (update) => latest.current(update)));
    return () => stops.forEach((stop) => stop());
  }, [realtime, key]);
}
