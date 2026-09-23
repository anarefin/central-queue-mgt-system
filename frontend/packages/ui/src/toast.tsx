"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { cn } from "./cn";

export type ToastVariant = "neutral" | "ok" | "danger";

export interface ToastProps {
  message: ReactNode;
  dismissLabel: string;
  onDismiss: () => void;
  durationMs?: number;
  variant?: ToastVariant;
}

const TOAST_VARIANT_CLASSES: Record<ToastVariant, string> = {
  neutral: "border-border",
  ok: "border-ok-subtle",
  danger: "border-danger-subtle",
};

/** Auto-dismisses after `durationMs`, but pauses for as long as it is hovered or focused (a visitor reading it never loses it mid-read). */
export function Toast({ message, dismissLabel, onDismiss, durationMs = 5000, variant = "neutral" }: ToastProps) {
  const [paused, setPaused] = useState(false);
  const remainingRef = useRef(durationMs);
  const startedAtRef = useRef(0);

  useEffect(() => {
    if (paused) {
      remainingRef.current = Math.max(remainingRef.current - (Date.now() - startedAtRef.current), 0);
      return;
    }
    startedAtRef.current = Date.now();
    const timer = setTimeout(onDismiss, remainingRef.current);
    return () => clearTimeout(timer);
  }, [paused, onDismiss]);

  return (
    <div
      role="status"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={() => setPaused(false)}
      className={cn("flex items-center gap-3 rounded-md border bg-surface px-4 py-3 text-fg shadow-md", TOAST_VARIANT_CLASSES[variant])}
    >
      <p className="text-sm">{message}</p>
      <button type="button" onClick={onDismiss} aria-label={dismissLabel} className="ms-auto rounded-sm text-fg-muted hover:text-fg focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">
        ×
      </button>
    </div>
  );
}
