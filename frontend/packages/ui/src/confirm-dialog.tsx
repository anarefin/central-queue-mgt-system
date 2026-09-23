"use client";

import { useEffect, useRef, type ReactNode } from "react";
import { Button } from "./components";

export interface ConfirmDialogProps {
  open: boolean;
  title: ReactNode;
  description?: ReactNode;
  confirmLabel: string;
  cancelLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
  danger?: boolean;
}

/**
 * Built on `<dialog>`: `showModal()` gives the focus trap and top-layer rendering natively, and the browser's own
 * `cancel` event fires on Esc. Focus returns to whatever triggered the dialog once it closes (ticket 62; replaces
 * `window.confirm` everywhere in later tickets).
 */
export function ConfirmDialog({ open, title, description, confirmLabel, cancelLabel, onConfirm, onCancel, danger }: ConfirmDialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const triggerRef = useRef<Element | null>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open) {
      triggerRef.current = document.activeElement;
      if (!dialog.open) dialog.showModal();
    } else if (dialog.open) {
      dialog.close();
    }
  }, [open]);

  useEffect(() => {
    if (open) return;
    const trigger = triggerRef.current;
    if (trigger instanceof HTMLElement) trigger.focus();
  }, [open]);

  return (
    <dialog
      ref={ref}
      onCancel={(event) => {
        event.preventDefault();
        onCancel();
      }}
      onClose={onCancel}
      className="rounded-lg border border-border bg-surface p-0 text-fg shadow-lg backdrop:bg-neutral-950/50"
    >
      <div className="flex flex-col gap-4 p-6">
        <h2 className="text-lg font-semibold">{title}</h2>
        {description && <p className="text-fg-muted">{description}</p>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onCancel}>
            {cancelLabel}
          </Button>
          <Button type="button" variant={danger ? "danger" : "primary"} onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </dialog>
  );
}
