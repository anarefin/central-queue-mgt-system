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
});
