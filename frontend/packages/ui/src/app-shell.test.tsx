import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { AppShell } from "./app-shell";

const NAV = [
  { href: "/sites/", label: "Sites", active: true },
  { href: "/devices/", label: "Devices" },
];

describe("AppShell", () => {
  it("renders the skip link as the very first focusable element, targeting #main", () => {
    render(
      <AppShell skipToContentLabel="Skip to content" menuButtonLabel="Menu" nav={NAV}>
        <p>Page content</p>
      </AppShell>,
    );
    const links = screen.getAllByRole("link");
    expect(links[0]).toHaveTextContent("Skip to content");
    expect(links[0]).toHaveAttribute("href", "#main");
  });

  it("renders a single main landmark holding the page content", () => {
    render(
      <AppShell skipToContentLabel="Skip to content" menuButtonLabel="Menu" nav={NAV}>
        <p>Page content</p>
      </AppShell>,
    );
    const main = screen.getByRole("main");
    expect(main).toHaveAttribute("id", "main");
    expect(main).toHaveTextContent("Page content");
  });

  it("marks the active nav item with aria-current", () => {
    render(
      <AppShell skipToContentLabel="Skip to content" menuButtonLabel="Menu" nav={NAV}>
        <p>Page content</p>
      </AppShell>,
    );
    expect(screen.getByRole("link", { name: "Sites" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Devices" })).not.toHaveAttribute("aria-current");
  });

  it("renders the title, site switcher, theme toggle and user menu slots", () => {
    render(
      <AppShell
        skipToContentLabel="Skip to content"
        menuButtonLabel="Menu"
        nav={NAV}
        title="Admin"
        siteSwitcher={<button type="button">Main campus</button>}
        userMenu={<button type="button">Jane Doe</button>}
        themeToggle={<button type="button">Theme</button>}
      >
        <p>Page content</p>
      </AppShell>,
    );
    expect(screen.getByText("Admin")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Main campus" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Jane Doe" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Theme" })).toBeInTheDocument();
  });

  it("toggles the mobile menu button's expanded state", async () => {
    render(
      <AppShell skipToContentLabel="Skip to content" menuButtonLabel="Menu" nav={NAV}>
        <p>Page content</p>
      </AppShell>,
    );
    const menuButton = screen.getByRole("button", { name: "Menu" });
    expect(menuButton).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(menuButton);
    expect(menuButton).toHaveAttribute("aria-expanded", "true");
  });
});
