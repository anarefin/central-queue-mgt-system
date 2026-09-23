import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { Tabs } from "./tabs";

const ITEMS = [
  { value: "general", label: "General", content: <p>General panel</p> },
  { value: "advanced", label: "Advanced", content: <p>Advanced panel</p> },
  { value: "danger", label: "Danger zone", content: <p>Danger panel</p> },
];

describe("Tabs", () => {
  it("shows the first tab's panel by default and hides the rest", () => {
    render(<Tabs items={ITEMS} label="Settings" />);
    expect(screen.getByText("General panel")).toBeVisible();
    expect(screen.getByText("Advanced panel")).not.toBeVisible();
  });

  it("switches panels on click and updates aria-selected", async () => {
    render(<Tabs items={ITEMS} label="Settings" />);
    await userEvent.click(screen.getByRole("tab", { name: "Advanced" }));
    expect(screen.getByRole("tab", { name: "Advanced" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "General" })).toHaveAttribute("aria-selected", "false");
    expect(screen.getByText("Advanced panel")).toBeVisible();
  });

  it("only the active tab is in the Tab order (roving tabindex)", () => {
    render(<Tabs items={ITEMS} label="Settings" />);
    expect(screen.getByRole("tab", { name: "General" })).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("tab", { name: "Advanced" })).toHaveAttribute("tabindex", "-1");
    expect(screen.getByRole("tab", { name: "Danger zone" })).toHaveAttribute("tabindex", "-1");
  });

  it("moves selection and focus with arrow keys, wrapping at the ends", async () => {
    const user = userEvent.setup();
    render(<Tabs items={ITEMS} label="Settings" />);
    screen.getByRole("tab", { name: "General" }).focus();

    await user.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { name: "Advanced" })).toHaveFocus();
    expect(screen.getByRole("tab", { name: "Advanced" })).toHaveAttribute("aria-selected", "true");

    await user.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { name: "Danger zone" })).toHaveFocus();

    await user.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { name: "General" })).toHaveFocus();

    await user.keyboard("{ArrowLeft}");
    expect(screen.getByRole("tab", { name: "Danger zone" })).toHaveFocus();
  });

  it("Home and End jump to the first and last tab", async () => {
    const user = userEvent.setup();
    render(<Tabs items={ITEMS} label="Settings" />);
    screen.getByRole("tab", { name: "General" }).focus();

    await user.keyboard("{End}");
    expect(screen.getByRole("tab", { name: "Danger zone" })).toHaveFocus();

    await user.keyboard("{Home}");
    expect(screen.getByRole("tab", { name: "General" })).toHaveFocus();
  });
});
