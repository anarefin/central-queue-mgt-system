import { waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { json, renderApp, stubApi } from "../test-utils";

afterEach(() => {
  document.documentElement.style.removeProperty("--color-primary");
});

describe("RuntimeProvider", () => {
  it("applies the organisation's brand once the public theme loads, before there is any session (ticket 62, FR-CFG-030)", async () => {
    stubApi({
      "GET /branding/theme": () => json(200, { org_name: "Northside Clinic", primary_color: "#123abc", logo_url: null }),
    });
    renderApp(<p>content</p>);
    await waitFor(() => expect(document.documentElement.style.getPropertyValue("--color-primary")).toBe("#123abc"));
  });

  it("leaves theme.css's own default accent alone (no inline override) when the theme fetch fails", async () => {
    stubApi({
      "GET /branding/theme": () => json(500, { error: { code: "unexpected", message: "x", trace_id: "t" } }),
    });
    renderApp(<p>content</p>);
    // No assertion can prove a *lack* of a future update, so this waits out one macrotask for the rejected
    // fetch's handler to have run, then checks no inline override was ever applied.
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(document.documentElement.style.getPropertyValue("--color-primary")).toBe("");
  });
});
