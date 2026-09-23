import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Toast } from "./toast";

describe("Toast", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("announces itself as a status region with the message", () => {
    render(<Toast message="Site saved" dismissLabel="Dismiss" onDismiss={vi.fn()} />);
    expect(screen.getByRole("status")).toHaveTextContent("Site saved");
  });

  it("dismisses itself automatically after the given duration", () => {
    const onDismiss = vi.fn();
    render(<Toast message="Site saved" dismissLabel="Dismiss" onDismiss={onDismiss} durationMs={3000} />);
    vi.advanceTimersByTime(2999);
    expect(onDismiss).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it("calls onDismiss when the dismiss button is clicked", () => {
    const onDismiss = vi.fn();
    render(<Toast message="Site saved" dismissLabel="Dismiss" onDismiss={onDismiss} />);
    fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));
    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it("pauses the auto-dismiss timer while hovered, and resumes the remaining time on leave", () => {
    const onDismiss = vi.fn();
    render(<Toast message="Site saved" dismissLabel="Dismiss" onDismiss={onDismiss} durationMs={3000} />);
    const status = screen.getByRole("status");

    vi.advanceTimersByTime(2000);
    fireEvent.mouseEnter(status);
    vi.advanceTimersByTime(5000);
    expect(onDismiss).not.toHaveBeenCalled();

    fireEvent.mouseLeave(status);
    vi.advanceTimersByTime(999);
    expect(onDismiss).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(onDismiss).toHaveBeenCalledOnce();
  });
});
