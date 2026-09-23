import { useCallback, useEffect, useSyncExternalStore } from "react";
import type { ApiClient } from "./client";

/** Every flag's org-wide master switch, by wire key (ticket 68, CFG-003, SRS §27.5). */
export type FeatureFlagMap = Record<string, boolean>;

interface FlagsState {
  flags: FeatureFlagMap | null;
  loading: boolean;
}

/**
 * One module-level cache shared by every component that calls {@link useFeatureFlags} in this app, so the flags are
 * read once per session rather than once per mounted section — the same "own the fetch once, let every reader
 * subscribe" shape {@code useSyncExternalStore} exists for. A kiosk or display never uses this: it already gets its
 * copy of the flags with the rest of {@code GET /config/bootstrap} and refetches that whole document on its own
 * device's {@code config.changed} push (FR-OPS-042); this hook is for a staff app (reception, setup) that calls
 * {@code GET /setup/feature-flags} directly and has no device topic of its own to listen on, so a caller instead
 * calls {@link FeatureFlagsHandle.refresh} right after changing a flag itself.
 */
let state: FlagsState = { flags: null, loading: false };
const listeners = new Set<() => void>();
let inFlight: Promise<void> | null = null;

function setState(next: FlagsState): void {
  state = next;
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function getSnapshot(): FlagsState {
  return state;
}

function load(client: ApiClient): Promise<void> {
  if (inFlight) return inFlight;
  setState({ flags: state.flags, loading: true });
  inFlight = client.setup
    .featureFlags()
    .then((flags) => setState({ flags, loading: false }))
    .catch(() => setState({ flags: state.flags, loading: false }))
    .finally(() => {
      inFlight = null;
    });
  return inFlight;
}

export interface FeatureFlagsHandle {
  /** {@code null} until the first load resolves. */
  flags: FeatureFlagMap | null;
  loading: boolean;
  /** Whether {@code key}'s org-wide master switch is on. While the flags have not loaded yet, answers {@code true}
   * (fail open) — the same default the backend itself uses for a key with no row (no vertical profile applied). */
  isEnabled: (key: string) => boolean;
  /** Re-fetches every flag. Call this right after a successful {@code PUT /setup/feature-flags/{key}}. */
  refresh: () => void;
}

/** Reads every feature flag's org-wide state once per session; see the module doc for the sharing and refresh shape. */
export function useFeatureFlags(client: ApiClient | null): FeatureFlagsHandle {
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);

  useEffect(() => {
    if (client && snapshot.flags === null && !snapshot.loading) void load(client);
  }, [client, snapshot.flags, snapshot.loading]);

  const refresh = useCallback(() => {
    if (client) void load(client);
  }, [client]);

  const isEnabled = useCallback((key: string) => snapshot.flags === null || snapshot.flags[key] !== false, [snapshot.flags]);

  return { flags: snapshot.flags, loading: snapshot.loading, isEnabled, refresh };
}

/** Test-only: clears the shared cache so one test's fetch never leaks into the next. */
export function resetFeatureFlagsCacheForTests(): void {
  state = { flags: null, loading: false };
  inFlight = null;
}
