import { useEffect, useRef } from "react";

/** The kiosk's own choice for the default (FR-ISS-015 names 45 s as the default, configurable; no admin surface for it yet). */
export const DEFAULT_INACTIVITY_TIMEOUT_MS = 45_000;

const ACTIVITY_EVENTS = ["pointerdown", "touchstart", "keydown"] as const;

/**
 * Returns the visitor to the idle screen, discarding whatever was mid-selection, after `timeoutMs` with no touch
 * (FR-ISS-015). Runs only while `active` is true — the idle screen itself needs no timer, and a screen already
 * showing the finished ticket resets the same way so the kiosk is ready for the next visitor without staff action.
 */
export function useInactivityTimeout(active: boolean, timeoutMs: number, onTimeout: () => void): void {
  const onTimeoutRef = useRef(onTimeout);
  onTimeoutRef.current = onTimeout;

  useEffect(() => {
    if (!active || typeof window === "undefined") return;
    let timer: ReturnType<typeof setTimeout>;
    const reset = () => {
      clearTimeout(timer);
      timer = setTimeout(() => onTimeoutRef.current(), timeoutMs);
    };
    reset();
    for (const event of ACTIVITY_EVENTS) window.addEventListener(event, reset);
    return () => {
      clearTimeout(timer);
      for (const event of ACTIVITY_EVENTS) window.removeEventListener(event, reset);
    };
  }, [active, timeoutMs]);
}
