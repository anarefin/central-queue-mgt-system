import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider, ThemeScript, useTheme } from "./theme-provider";

function Probe() {
  const { choice, resolved, setChoice } = useTheme();
  return (
    <div>
      <p>
        choice: {choice}, resolved: {resolved}
      </p>
      <button type="button" onClick={() => setChoice("light")}>
        Light
      </button>
      <button type="button" onClick={() => setChoice("dark")}>
        Dark
      </button>
      <button type="button" onClick={() => setChoice("system")}>
        System
      </button>
    </div>
  );
}

describe("ThemeProvider", () => {
  afterEach(() => {
    document.documentElement.removeAttribute("data-theme");
    window.localStorage.clear();
  });

  it("defaults to system with no data-theme attribute", () => {
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(screen.getByText(/choice: system/)).toBeInTheDocument();
    expect(document.documentElement).not.toHaveAttribute("data-theme");
  });

  it("reads a previously saved choice from localStorage", () => {
    window.localStorage.setItem("qms-theme", "dark");
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(screen.getByText(/choice: dark, resolved: dark/)).toBeInTheDocument();
    expect(document.documentElement).toHaveAttribute("data-theme", "dark");
  });

  it("setting light or dark updates data-theme and persists the choice", async () => {
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    await userEvent.click(screen.getByRole("button", { name: "Dark" }));
    expect(document.documentElement).toHaveAttribute("data-theme", "dark");
    expect(window.localStorage.getItem("qms-theme")).toBe("dark");

    await userEvent.click(screen.getByRole("button", { name: "Light" }));
    expect(document.documentElement).toHaveAttribute("data-theme", "light");
    expect(window.localStorage.getItem("qms-theme")).toBe("light");
  });

  it("setting system removes the data-theme attribute again", async () => {
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    await userEvent.click(screen.getByRole("button", { name: "Dark" }));
    await userEvent.click(screen.getByRole("button", { name: "System" }));
    expect(document.documentElement).not.toHaveAttribute("data-theme");
    expect(window.localStorage.getItem("qms-theme")).toBe("system");
  });

  describe("when localStorage throws", () => {
    beforeEach(() => {
      vi.spyOn(window.localStorage.__proto__, "getItem").mockImplementation(() => {
        throw new Error("storage disabled");
      });
      vi.spyOn(window.localStorage.__proto__, "setItem").mockImplementation(() => {
        throw new Error("storage disabled");
      });
    });

    afterEach(() => {
      vi.restoreAllMocks();
    });

    it("still renders, falling back to system", () => {
      render(
        <ThemeProvider>
          <Probe />
        </ThemeProvider>,
      );
      expect(screen.getByText(/choice: system/)).toBeInTheDocument();
    });

    it("still updates the in-memory choice for this run even though nothing persists", async () => {
      render(
        <ThemeProvider>
          <Probe />
        </ThemeProvider>,
      );
      await userEvent.click(screen.getByRole("button", { name: "Dark" }));
      expect(document.documentElement).toHaveAttribute("data-theme", "dark");
    });
  });
});

describe("ThemeScript", () => {
  it("renders a script tag with the theme-init snippet", () => {
    const { container } = render(<ThemeScript />);
    const script = container.querySelector("script");
    expect(script).not.toBeNull();
    expect(script?.innerHTML).toContain("qms-theme");
  });
});
