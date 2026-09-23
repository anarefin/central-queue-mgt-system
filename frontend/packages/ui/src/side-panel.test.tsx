import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SidePanel } from "./side-panel";

// jsdom does not implement <dialog>'s showModal()/close() (see confirm-dialog.test.tsx for why); stubbed the same way.
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
    this.setAttribute("open", "");
  });
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
    this.removeAttribute("open");
    this.dispatchEvent(new Event("close"));
  });
});

function Harness({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <div>
      <button type="button">Transfer</button>
      {open && (
        <SidePanel label="Transfer ticket" onClose={onClose}>
          <h2>Transfer</h2>
          <input aria-label="Note" />
        </SidePanel>
      )}
    </div>
  );
}

describe("SidePanel", () => {
  it("opens as a modal dialog holding its content", () => {
    render(
      <SidePanel label="Transfer ticket" onClose={vi.fn()}>
        <p>Panel content</p>
      </SidePanel>,
    );
    const dialog = screen.getByRole("dialog", { hidden: true });
    expect(dialog).toHaveAttribute("open");
    expect(dialog).toHaveAttribute("aria-label", "Transfer ticket");
    expect(screen.getByText("Panel content")).toBeInTheDocument();
  });

  it("closes on Escape", async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(
      <SidePanel label="Transfer ticket" onClose={onClose}>
        <input aria-label="Note" />
      </SidePanel>,
    );
    await user.type(screen.getByLabelText("Note"), "x{Escape}");
    expect(onClose).toHaveBeenCalled();
  });

  it("returns focus to the element that triggered it once it is gone", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const { rerender } = render(<Harness open={false} onClose={onClose} />);
    const trigger = screen.getByRole("button", { name: "Transfer" });
    await user.click(trigger);
    expect(trigger).toHaveFocus();

    rerender(<Harness open onClose={onClose} />);
    rerender(<Harness open={false} onClose={onClose} />);
    expect(trigger).toHaveFocus();
  });
});
