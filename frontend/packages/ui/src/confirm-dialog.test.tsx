import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ConfirmDialog } from "./confirm-dialog";

// jsdom does not implement <dialog>'s showModal()/close() (they are no-ops that throw or do nothing), so this
// stubs just enough of the real behaviour (the `open` attribute, and a `close` event on close()) for the
// component's own effects to observe, without re-implementing the browser's focus trap.
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
    this.setAttribute("open", "");
  });
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
    this.removeAttribute("open");
    this.dispatchEvent(new Event("close"));
  });
});

function Harness({ open, onConfirm, onCancel }: { open: boolean; onConfirm: () => void; onCancel: () => void }) {
  return (
    <div>
      <button type="button">Delete site</button>
      <ConfirmDialog open={open} title="Delete this site?" description="This cannot be undone." confirmLabel="Delete" cancelLabel="Cancel" onConfirm={onConfirm} onCancel={onCancel} danger />
    </div>
  );
}

describe("ConfirmDialog", () => {
  it("opens as a modal dialog with its title and description", () => {
    render(<ConfirmDialog open title="Delete this site?" description="This cannot be undone." confirmLabel="Delete" cancelLabel="Cancel" onConfirm={vi.fn()} onCancel={vi.fn()} />);
    expect(screen.getByRole("dialog", { hidden: true })).toHaveAttribute("open");
    expect(screen.getByText("Delete this site?")).toBeInTheDocument();
    expect(screen.getByText("This cannot be undone.")).toBeInTheDocument();
  });

  it("calls onConfirm when the confirm button is clicked", async () => {
    const onConfirm = vi.fn();
    render(<ConfirmDialog open title="Delete this site?" confirmLabel="Delete" cancelLabel="Cancel" onConfirm={onConfirm} onCancel={vi.fn()} />);
    await userEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it("calls onCancel when the cancel button is clicked", async () => {
    const onCancel = vi.fn();
    render(<ConfirmDialog open title="Delete this site?" confirmLabel="Delete" cancelLabel="Cancel" onConfirm={vi.fn()} onCancel={onCancel} />);
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onCancel).toHaveBeenCalledOnce();
  });

  it("calls onCancel when Esc fires the native cancel event", () => {
    const onCancel = vi.fn();
    render(<ConfirmDialog open title="Delete this site?" confirmLabel="Delete" cancelLabel="Cancel" onConfirm={vi.fn()} onCancel={onCancel} />);
    const dialog = screen.getByRole("dialog", { hidden: true });
    dialog.dispatchEvent(new Event("cancel", { cancelable: true }));
    expect(onCancel).toHaveBeenCalledOnce();
  });

  it("returns focus to the element that was focused before the dialog opened", () => {
    const onCancel = vi.fn();
    const { rerender } = render(<Harness open={false} onConfirm={vi.fn()} onCancel={onCancel} />);
    const trigger = screen.getByRole("button", { name: "Delete site" });
    trigger.focus();
    expect(trigger).toHaveFocus();

    rerender(<Harness open onConfirm={vi.fn()} onCancel={onCancel} />);
    rerender(<Harness open={false} onConfirm={vi.fn()} onCancel={onCancel} />);
    expect(trigger).toHaveFocus();
  });
});
