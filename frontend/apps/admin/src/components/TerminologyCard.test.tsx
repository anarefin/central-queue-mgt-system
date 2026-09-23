import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { TerminologyCard } from "./TerminologyCard";

const AUTH: Routes = {
  "POST /auth/refresh": () => json(200, { access_token: "tok", token_type: "Bearer", expires_in: 900 }),
  "GET /auth/me": () => json(200, { id: "u1", username: "asha", display_name: "Asha Rahman", preferred_language: null, roles: ["org_admin"], sites: ["s1"], groups: [] }),
};

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function fakeApi(state: { en: Record<string, string>; bn: Record<string, string> }, extra: Routes = {}): Recorded[] {
  return stubApi({
    ...AUTH,
    "GET /labels?lang=en": () => json(200, state.en),
    "GET /labels?lang=bn": () => json(200, state.bn),
    ...extra,
  });
}

/** The `<tr>` whose row-heading cell (the key's own English default noun) is exactly `heading` — never confused
 * with a value cell that happens to show the same text (e.g. an unedited default matches its own heading). */
async function rowFor(heading: string): Promise<HTMLElement> {
  const rows = await screen.findAllByRole("row");
  const match = rows.find((row) => within(row).queryAllByRole("cell")[0]?.textContent === heading);
  if (!match) throw new Error(`no row headed "${heading}"`);
  return match;
}

describe("terminology admin screen (SRS §3.2, ticket 69)", () => {
  it("shows every entity key's pack default when no override exists", async () => {
    fakeApi({ en: {}, bn: {} });
    renderApp(<TerminologyCard />);

    const visitorRow = await rowFor("Visitor");
    expect(within(visitorRow).getAllByText("(default)")).toHaveLength(2); // en and bn columns

    const ticketRow = await rowFor("Token");
    expect(within(ticketRow).getAllByText("(default)")).toHaveLength(2);
  });

  it("shows a saved override instead of the default, with no (default) marker on that cell", async () => {
    fakeApi({ en: { "entity.visitor": "Customer" }, bn: {} });
    renderApp(<TerminologyCard />);

    const row = await rowFor("Visitor");
    const [, enCell, bnCell] = within(row).getAllByRole("cell");
    expect(within(enCell!).getByText("Customer")).toBeInTheDocument();
    expect(within(enCell!).queryByText("(default)")).not.toBeInTheDocument();
    expect(within(bnCell!).getByText("(default)")).toBeInTheDocument(); // bn column still has no override
  });

  it("edits a key's value, saves it through PUT /labels/{key}, and reflects it immediately", async () => {
    const state = { en: {}, bn: {} };
    const calls = fakeApi(state, {
      "PUT /labels/entity.counter": (init) => {
        const body = JSON.parse(String(init.body)) as { lang: string; value: string };
        state.en = { ...state.en, "entity.counter": body.value };
        return json(200, state.en);
      },
    });
    renderApp(<TerminologyCard />);

    const row = await rowFor("Counter");
    const enCell = within(row).getAllByRole("cell")[1]!;
    await userEvent.click(within(enCell).getByRole("button", { name: "Edit" }));
    const field = await screen.findByLabelText("Value");
    await userEvent.clear(field);
    await userEvent.type(field, "Desk");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    await within(await rowFor("Counter")).findByText("Desk");
    const put = calls.find((c) => c.method === "PUT" && c.path === "/labels/entity.counter");
    expect(JSON.parse(String(put?.init.body))).toEqual({ lang: "en", value: "Desk" });
  });

  it("blocks a blank or too-long value client-side, mirroring the server's own rule (ticket 66)", async () => {
    fakeApi({ en: {}, bn: {} });
    renderApp(<TerminologyCard />);

    const row = await rowFor("Agent");
    const enCell = within(row).getAllByRole("cell")[1]!;
    await userEvent.click(within(enCell).getByRole("button", { name: "Edit" }));
    const field = await screen.findByLabelText("Value");
    await userEvent.clear(field);
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toBeInTheDocument();

    await userEvent.type(field, "x".repeat(61));
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });

  it("resets an override back to the pack default through DELETE /labels/{key}", async () => {
    const state: { en: Record<string, string>; bn: Record<string, string> } = { en: { "entity.ticket": "Coupon" }, bn: {} };
    const calls = fakeApi(state, {
      "DELETE /labels/entity.ticket?lang=en": () => {
        state.en = {};
        return json(200, state.en);
      },
    });
    renderApp(<TerminologyCard />);

    const row = await rowFor("Token");
    const enCell = within(row).getAllByRole("cell")[1]!;
    expect(within(enCell).getByText("Coupon")).toBeInTheDocument();
    await userEvent.click(within(enCell).getByRole("button", { name: "Reset" }));

    const enCellAfter = within(await rowFor("Token")).getAllByRole("cell")[1]!;
    await within(enCellAfter).findByText("(default)");
    expect(within(enCellAfter).queryByText("Coupon")).not.toBeInTheDocument();
    expect(calls.some((c) => c.method === "DELETE" && c.path === "/labels/entity.ticket?lang=en")).toBe(true);
  });

  it("shows a live preview sentence using the current entity terms", async () => {
    fakeApi({ en: { "entity.visitor": "Customer" }, bn: {} });
    renderApp(<TerminologyCard />);

    expect(await screen.findByText(/joins the queue/)).toHaveTextContent("A customer joins the queue for the service group.");
  });
});
