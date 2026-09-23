"use client";

import { useEffect, useRef, type ReactNode } from "react";
import { cn } from "./cn";

export interface SidePanelProps {
  /** The dialog's own accessible name (its heading is inside `children`, but a `<dialog>` needs one of its own). */
  label: string;
  children: ReactNode;
  onClose: () => void;
  className?: string;
}

/**
 * A side drawer built on `<dialog>` (ticket 64): opens with `showModal()` on mount, so it gets the browser's native
 * focus trap and top-layer rendering the same way {@link ConfirmDialog} already does, and closes with Esc — the
 * caller decides when to stop rendering it (as every panel already did before this ticket), so this only mounts
 * while open. Focus returns to whatever triggered it once it is gone, the same contract `ConfirmDialog` keeps.
 */
export function SidePanel({ label, children, onClose, className }: SidePanelProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const triggerRef = useRef<Element | null>(null);

  useEffect(() => {
    triggerRef.current = document.activeElement;
    const dialog = ref.current;
    if (dialog && !dialog.open) dialog.showModal();
    return () => {
      if (dialog?.open) dialog.close();
      const trigger = triggerRef.current;
      if (trigger instanceof HTMLElement) trigger.focus();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <dialog
      ref={ref}
      aria-label={label}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      onKeyDown={(event) => {
        if (event.key === "Escape") onClose();
      }}
      className={cn(
        "fixed inset-y-0 end-0 m-0 flex h-full w-full max-w-md flex-col border-0 border-s border-border bg-surface p-0 text-fg shadow-lg backdrop:bg-neutral-950/50",
        "sm:inset-y-4 sm:end-4 sm:h-auto sm:max-h-[calc(100dvh-2rem)] sm:rounded-lg sm:border",
        className,
      )}
    >
      <div className="flex max-h-[inherit] flex-col gap-4 overflow-y-auto p-6">{children}</div>
    </dialog>
  );
}
