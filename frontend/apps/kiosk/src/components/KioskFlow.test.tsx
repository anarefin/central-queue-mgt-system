import { ApiClient, type DeviceBootstrap, type Ticket } from "@qms/api-client";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { KioskFlow } from "./KioskFlow";
import type { TokenPrinter } from "../lib/token-printer";

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function ticketResponse(overrides: Partial<Ticket> = {}): Ticket {
  return {
    id: "tk-1",
    token_number: "A-001",
    state: "waiting",
    service: { id: "svc-1", name_i18n: { en: "Consultation", bn: "পরামর্শ" } },
    service_group: { id: "grp-1", name_i18n: { en: "Outpatient", bn: "বহির্বিভাগ" } },
    site_id: "site-1",
    zone: null,
    visit_id: "visit-1",
    origin_channel: "kiosk",
    priority_class: null,
    position: 1,
    estimated_wait_minutes: { low: 0, high: 5 },
    issued_at: "2026-09-19T10:00:00Z",
    queued_at: "2026-09-19T10:00:00Z",
    version: 1,
    secret: "s3cr3t-0123456789012345678901234567890",
    ...overrides,
  };
}

/** Shorthand for a bootstrap Service with no selection-tree extras (ticket 25's baseline behaviour, ticket 26 default). */
function service(id: string, nameI18n: Record<string, string>, visitorIdentifier: "not_required" | "optional" | "mandatory" = "not_required") {
  return { id, name_i18n: nameI18n, visitor_identifier: visitorIdentifier };
}

/** Shorthand for a bootstrap group with every ticket-26 level left off, unless overridden. */
function group(
  id: string,
  nameI18n: Record<string, string>,
  services: ReturnType<typeof service>[],
  overrides: Partial<Pick<DeviceBootstrap["service_tree"][number], "team_selectable" | "individual_selectable" | "custom_level">> = {},
) {
  return { id, name_i18n: nameI18n, services, team_selectable: false, individual_selectable: false, custom_level: null, ...overrides };
}

const SINGLE: DeviceBootstrap = {
  branding: { site_name: "Main campus", default_language: "en" },
  languages: ["en"],
  layout: null,
  service_tree: [group("grp-1", { en: "Outpatient" }, [service("svc-1", { en: "Consultation" })])],
};

const MULTI_LANGUAGE_SINGLE_GROUP: DeviceBootstrap = {
  branding: { site_name: "Main campus", default_language: "en" },
  languages: ["en", "bn"],
  layout: null,
  service_tree: [
    group("grp-1", { en: "Outpatient", bn: "বহির্বিভাগ" }, [
      service("svc-1", { en: "Consultation", bn: "পরামর্শ" }),
      service("svc-2", { en: "Pharmacy", bn: "ফার্মেসি" }),
    ]),
  ],
};

const TWO_GROUPS: DeviceBootstrap = {
  branding: { site_name: "Main campus", default_language: "en" },
  languages: ["en"],
  layout: null,
  service_tree: [
    group("grp-1", { en: "Outpatient" }, [service("svc-1", { en: "Consultation" }), service("svc-2", { en: "Pharmacy" })]),
    group("grp-2", { en: "Records" }, [service("svc-3", { en: "Certificates" })]),
  ],
};

const EMPTY: DeviceBootstrap = {
  branding: { site_name: "Main campus", default_language: "en" },
  languages: ["en"],
  layout: null,
  service_tree: [],
};

function clientWith(fetchImpl: ReturnType<typeof vi.fn>): ApiClient {
  return new ApiClient({ apiOrigin: "", fetch: fetchImpl as unknown as typeof fetch, getAccessToken: () => "kiosk-token" });
}

/** The token as the visitor actually sees it: the print-only slip (`.qms-print-slip`) repeats the same text hidden from the screen. */
function visibleToken(token: string): HTMLElement {
  const matches = screen.getAllByText(token);
  const onScreen = matches.find((el) => !el.closest(".qms-print-slip"));
  if (!onScreen) throw new Error(`"${token}" was not found outside the print-only slip`);
  return onScreen;
}

class RejectingPrinter implements TokenPrinter {
  async print(): Promise<void> {
    throw new Error("no printer attached");
  }
}

class ResolvingPrinter implements TokenPrinter {
  calls: unknown[] = [];
  async print(payload: Parameters<TokenPrinter["print"]>[0]): Promise<void> {
    this.calls.push(payload);
  }
}

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("KioskFlow common path (ticket 25, SRS §8.2)", () => {
  it("fully skips the language, group and service steps when each resolves to one option (NFR-USA-001: well under 4 taps)", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(json(201, ticketResponse()));
    const printer = new ResolvingPrinter();
    render(<KioskFlow bootstrap={SINGLE} client={clientWith(fetchImpl)} printer={printer} />);

    // Tap 1: the idle screen has no language choice (one language only), just a single start tile.
    expect(screen.queryByText("English")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" }));

    // Landed straight on confirm: no group screen (one group) and no service screen (one service in it).
    expect(await screen.findByText("Confirm your token")).toBeInTheDocument();
    expect(screen.getByText(/Outpatient/)).toBeInTheDocument();
    expect(screen.getByText(/Consultation/)).toBeInTheDocument();

    // Tap 2: confirm and print.
    await userEvent.click(screen.getByRole("button", { name: "Get my token" }));

    expect(await screen.findByText("Take your ticket from the printer")).toBeInTheDocument();
    expect(visibleToken("A-001")).toBeInTheDocument();
    expect(printer.calls).toEqual([{ tokenNumber: "A-001", serviceName: "Consultation", groupName: "Outpatient" }]);
    const [url, init] = fetchImpl.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("/kiosk/tickets");
    expect(JSON.parse(String(init.body))).toEqual({ service_id: "svc-1" });
    expect((init.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy();
  });

  it("offers a language choice on the idle screen when more than one language is enabled, and the pick carries into later screens", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(json(201, ticketResponse()));
    render(<KioskFlow bootstrap={MULTI_LANGUAGE_SINGLE_GROUP} client={clientWith(fetchImpl)} printer={new ResolvingPrinter()} />);

    expect(screen.getByRole("button", { name: "English" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "বাংলা" })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "বাংলা" }));

    // The group is skipped (only one), so this lands on the service screen, now in Bangla.
    expect(await screen.findByText("একটি সেবা বেছে নিন")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "পরামর্শ" })).toBeInTheDocument();
  });

  it("walks Group → Service → Confirm within 4 taps when both have more than one option", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(json(201, ticketResponse({ token_number: "R-004" })));
    render(<KioskFlow bootstrap={TWO_GROUPS} client={clientWith(fetchImpl)} printer={new ResolvingPrinter()} />);

    let taps = 0;
    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" })); // tap 1: leave idle
    taps++;
    expect(await screen.findByText("Choose a service group")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Outpatient" })); // tap 2: group
    taps++;
    expect(await screen.findByText("Choose a service")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Pharmacy" })); // tap 3: service
    taps++;
    expect(await screen.findByText("Confirm your token")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Get my token" })); // tap 4: confirm
    taps++;

    expect(taps).toBeLessThanOrEqual(4);
    await waitFor(() => visibleToken("R-004"));
  });

  it("shows nothing to pick, with a way back to idle, when the kiosk has no eligible services", async () => {
    render(<KioskFlow bootstrap={EMPTY} client={clientWith(vi.fn())} printer={new ResolvingPrinter()} />);

    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" }));

    expect(await screen.findByText("No services are available at this kiosk right now.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Back" }));
    expect(await screen.findByRole("button", { name: "Tap to begin" })).toBeInTheDocument();
  });
});

describe("KioskFlow printer failure fallback (FR-ISS-016)", () => {
  it("still creates the Ticket and shows the token large with a scannable QR when printing fails", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(json(201, ticketResponse({ token_number: "B-007", secret: "the-secret" })));
    render(<KioskFlow bootstrap={SINGLE} client={clientWith(fetchImpl)} printer={new RejectingPrinter()} />);

    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" }));
    await userEvent.click(await screen.findByRole("button", { name: "Get my token" }));

    expect(await screen.findByText("The printer is not available, but your token is saved")).toBeInTheDocument();
    expect(visibleToken("B-007")).toBeInTheDocument();
    // The ticket was issued through the API regardless of the print outcome.
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const qr = screen.getByRole("img", { name: /B-007/ });
    expect(qr.tagName.toLowerCase()).toBe("svg");
  });
});

describe("KioskFlow issuance failure and retry", () => {
  it("shows the server's localised reason and retries with the same Idempotency-Key", async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(
        json(409, { error: { code: "conflict", message: "closed", message_i18n: { en: "Sorry, closed." }, trace_id: "t1" } }),
      )
      .mockResolvedValueOnce(json(201, ticketResponse()));
    render(<KioskFlow bootstrap={SINGLE} client={clientWith(fetchImpl)} printer={new ResolvingPrinter()} />);

    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" }));
    await userEvent.click(await screen.findByRole("button", { name: "Get my token" }));

    expect(await screen.findByText("Sorry, closed.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByText("Take your ticket from the printer")).toBeInTheDocument();
    const key = (call: unknown) => ((call as [string, RequestInit])[1].headers as Record<string, string>)["Idempotency-Key"];
    expect(key(fetchImpl.mock.calls[0])).toBe(key(fetchImpl.mock.calls[1]));
  });

  it("lets the visitor start over instead of retrying", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(json(500, { error: { code: "internal_error", message: "x", trace_id: "t" } }));
    render(<KioskFlow bootstrap={SINGLE} client={clientWith(fetchImpl)} printer={new ResolvingPrinter()} />);

    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" }));
    await userEvent.click(await screen.findByRole("button", { name: "Get my token" }));
    expect(await screen.findByText("We could not get your token")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Start over" }));

    expect(await screen.findByRole("button", { name: "Tap to begin" })).toBeInTheDocument();
  });
});

describe("KioskFlow inactivity (FR-ISS-015)", () => {
  it("returns to idle and discards the partial selection after the inactivity timeout", async () => {
    render(<KioskFlow bootstrap={TWO_GROUPS} client={clientWith(vi.fn())} printer={new ResolvingPrinter()} inactivityTimeoutMs={1000} />);
    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" }));
    await userEvent.click(await screen.findByRole("button", { name: "Outpatient" }));
    expect(await screen.findByText("Choose a service")).toBeInTheDocument();

    vi.useFakeTimers();
    vi.advanceTimersByTime(1100);
    vi.useRealTimers();

    expect(await screen.findByRole("button", { name: "Tap to begin" })).toBeInTheDocument();
    expect(screen.queryByText("Choose a service")).not.toBeInTheDocument();
  });

  it("never times out on the idle screen itself", async () => {
    render(<KioskFlow bootstrap={SINGLE} client={clientWith(vi.fn())} printer={new ResolvingPrinter()} inactivityTimeoutMs={10} />);
    await screen.findByRole("button", { name: "Tap to begin" });

    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(screen.getByRole("button", { name: "Tap to begin" })).toBeInTheDocument();
  });
});

describe("KioskFlow accessibility (FR-ISS-017, NFR-USA-003)", () => {
  it("every idle and confirm tile is at least a 48x48 px touch target", async () => {
    render(<KioskFlow bootstrap={SINGLE} client={clientWith(vi.fn())} printer={new ResolvingPrinter()} />);
    const start = screen.getByRole("button", { name: "Tap to begin" });
    expect(start).toHaveClass("qms-kiosk-tile");

    await userEvent.click(start);
    for (const button of await screen.findAllByRole("button")) {
      if (button.className.includes("qms-kiosk-a11y-toggle")) continue;
      expect(button).toHaveClass("qms-kiosk-tile");
    }
  });

  it("toggles high-contrast and larger-text mode, and remembers the choice across a remount (device setting, not a visitor one)", async () => {
    const { unmount } = render(<KioskFlow bootstrap={SINGLE} client={clientWith(vi.fn())} printer={new ResolvingPrinter()} />);
    const root = document.querySelector(".qms-kiosk") as HTMLElement;
    expect(root).toHaveAttribute("data-contrast", "normal");

    await userEvent.click(screen.getByRole("button", { name: "High contrast" }));
    await userEvent.click(screen.getByRole("button", { name: "Large text" }));
    expect(root).toHaveAttribute("data-contrast", "high");
    expect(root).toHaveClass("qms-kiosk--large-text");
    unmount();

    render(<KioskFlow bootstrap={SINGLE} client={clientWith(vi.fn())} printer={new ResolvingPrinter()} />);
    const rootAfterReload = document.querySelector(".qms-kiosk") as HTMLElement;
    expect(rootAfterReload).toHaveAttribute("data-contrast", "high");
    expect(rootAfterReload).toHaveClass("qms-kiosk--large-text");
  });
});

describe("KioskFlow recovery (NFR-AVL-006)", () => {
  it("a fresh mount always starts at idle, with no partial ticket-flow state to recover", async () => {
    render(<KioskFlow bootstrap={TWO_GROUPS} client={clientWith(vi.fn())} printer={new ResolvingPrinter()} />);
    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" }));
    await userEvent.click(await screen.findByRole("button", { name: "Outpatient" }));
    expect(await screen.findByText("Choose a service")).toBeInTheDocument();

    // Simulates the kiosk losing power mid-flow and coming back up: a fresh mount, no staff action, no leftover state.
    render(<KioskFlow bootstrap={TWO_GROUPS} client={clientWith(vi.fn())} printer={new ResolvingPrinter()} />);

    const idleButtons = screen.getAllByRole("button", { name: "Tap to begin" });
    expect(idleButtons.length).toBeGreaterThan(0);
  });
});

describe("KioskFlow idle-to-first-touch (NFR-PERF-007)", () => {
  it("mounts with the idle screen's tap target already interactive, before anything async settles", () => {
    render(<KioskFlow bootstrap={SINGLE} client={clientWith(vi.fn())} printer={new ResolvingPrinter()} />);

    // No `findBy`/await here: the very first synchronous render already has a clickable start tile, so a real
    // device's first touch is never waiting on a promise (bootstrap is already-loaded, passed in as a prop).
    const start = screen.getByRole("button", { name: "Tap to begin" });
    expect(start).toBeEnabled();
  });
});

// ---- ticket 26: identification and the rest of the selection tree ---------------------------------------------

/** Routes a fake `fetch` by method and a substring of the URL, mirroring the other kiosk suites' `stubApi`. */
function routedFetch(routes: Record<string, (init: RequestInit) => Response | Promise<Response>>) {
  return vi.fn(async (url: string, init: RequestInit = {}) => {
    const method = init.method ?? "GET";
    for (const [key, handler] of Object.entries(routes)) {
      const spaceAt = key.indexOf(" ");
      const routeMethod = key.slice(0, spaceAt);
      const routePath = key.slice(spaceAt + 1);
      if (routeMethod === method && url.includes(routePath)) return handler(init);
    }
    throw new Error(`unrouted ${method} ${url}`);
  });
}

function issuedBody(fetchImpl: ReturnType<typeof vi.fn>): unknown {
  const call = fetchImpl.mock.calls.find(([url]) => String(url).includes("/kiosk/tickets"));
  if (!call) throw new Error("no /kiosk/tickets call recorded");
  return JSON.parse(String((call[1] as RequestInit).body));
}

const OPTIONAL_ID_GROUP: DeviceBootstrap = {
  branding: { site_name: "Main campus", default_language: "en" },
  languages: ["en"],
  layout: null,
  service_tree: [group("grp-1", { en: "Outpatient" }, [service("svc-1", { en: "Consultation" }, "optional")])],
};

const MANDATORY_ID_GROUP: DeviceBootstrap = {
  branding: { site_name: "Main campus", default_language: "en" },
  languages: ["en"],
  layout: null,
  service_tree: [group("grp-1", { en: "Outpatient" }, [service("svc-1", { en: "Consultation" }, "mandatory")])],
};

const INDIVIDUAL_GROUP: DeviceBootstrap = {
  branding: { site_name: "Main campus", default_language: "en" },
  languages: ["en"],
  layout: null,
  service_tree: [
    group("grp-1", { en: "Outpatient" }, [service("svc-1", { en: "Consultation" })], { team_selectable: true, individual_selectable: true }),
  ],
};

const CUSTOM_LEVEL_GROUP: DeviceBootstrap = {
  branding: { site_name: "Main campus", default_language: "en" },
  languages: ["en"],
  layout: null,
  service_tree: [
    group("grp-1", { en: "Outpatient" }, [service("svc-1", { en: "Consultation" })], {
      custom_level: {
        name_i18n: { en: "Preferred language" },
        options: [
          { id: "bn", name_i18n: { en: "Bangla" } },
          { id: "en", name_i18n: { en: "English" } },
        ],
      },
    }),
  ],
};

const SINGLE_CUSTOM_OPTION_GROUP: DeviceBootstrap = {
  branding: { site_name: "Main campus", default_language: "en" },
  languages: ["en"],
  layout: null,
  service_tree: [
    group("grp-1", { en: "Outpatient" }, [service("svc-1", { en: "Consultation" })], {
      custom_level: { name_i18n: { en: "Preferred language" }, options: [{ id: "en", name_i18n: { en: "English" } }] },
    }),
  ],
};

describe("KioskFlow identification (ticket 26, FR-ISS-013, FR-ISS-014, FR-CFG-013)", () => {
  it("skips the identify step entirely for a Service that does not require one", async () => {
    render(<KioskFlow bootstrap={SINGLE} client={clientWith(vi.fn().mockResolvedValue(json(201, ticketResponse())))} printer={new ResolvingPrinter()} />);
    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" }));
    expect(await screen.findByText("Confirm your token")).toBeInTheDocument();
    expect(screen.queryByText("Identify yourself")).not.toBeInTheDocument();
  });

  it("resolves a typed code to only a name and category, carries it to confirm, and sends visitor_id when issuing", async () => {
    const fetchImpl = routedFetch({
      "GET /kiosk/visitors/identify": () => json(200, { visitor_id: "vis-1", name: "Karim Rahman", category: "citizen" }),
      "POST /kiosk/tickets": () => json(201, ticketResponse()),
    });
    render(<KioskFlow bootstrap={OPTIONAL_ID_GROUP} client={clientWith(fetchImpl)} printer={new ResolvingPrinter()} />);

    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" }));
    expect(await screen.findByText("Identify yourself")).toBeInTheDocument();
    expect(screen.getByText("You may identify yourself, or continue without it.")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Enter code" }));
    await userEvent.type(screen.getByLabelText("Type your code"), "V-CODE-1");
    await userEvent.click(screen.getByRole("button", { name: "Look up" }));

    expect(await screen.findByText("Confirm your token")).toBeInTheDocument();
    expect(screen.getByText("Identified as: Karim Rahman (citizen)")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Get my token" }));
    await waitFor(() => expect(issuedBody(fetchImpl)).toMatchObject({ service_id: "svc-1", visitor_id: "vis-1" }));
  });

  it("lets the visitor skip identification when it is only optional", async () => {
    const fetchImpl = routedFetch({ "POST /kiosk/tickets": () => json(201, ticketResponse()) });
    render(<KioskFlow bootstrap={OPTIONAL_ID_GROUP} client={clientWith(fetchImpl)} printer={new ResolvingPrinter()} />);

    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" }));
    await userEvent.click(await screen.findByRole("button", { name: "Continue without identification" }));

    expect(await screen.findByText("Confirm your token")).toBeInTheDocument();
    expect(screen.queryByText(/Identified as/)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Get my token" }));
    await waitFor(() => expect(issuedBody(fetchImpl)).toEqual({ service_id: "svc-1" }));
  });

  it("never offers to skip when identification is mandatory, and shows a not-found message that can be retried", async () => {
    const fetchImpl = routedFetch({
      "GET /kiosk/visitors/identify": () => json(404, { error: { code: "not_found", message: "x", trace_id: "t" } }),
    });
    render(<KioskFlow bootstrap={MANDATORY_ID_GROUP} client={clientWith(fetchImpl)} printer={new ResolvingPrinter()} />);

    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" }));
    expect(await screen.findByText("This service requires you to identify yourself first.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Continue without identification" })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Enter code" }));
    await userEvent.type(screen.getByLabelText("Type your code"), "NOPE");
    await userEvent.click(screen.getByRole("button", { name: "Look up" }));

    expect(await screen.findByText("We could not find that. Check it and try again.")).toBeInTheDocument();
  });

  it("hides the QR scan option when the browser cannot detect a barcode", async () => {
    render(<KioskFlow bootstrap={OPTIONAL_ID_GROUP} client={clientWith(vi.fn())} printer={new ResolvingPrinter()} />);
    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" }));
    expect(await screen.findByText("Identify yourself")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Scan QR code" })).not.toBeInTheDocument();
  });
});

describe("KioskFlow individual Agent level (ticket 26, FR-ISS-012)", () => {
  it("offers only the on-duty Agents plus 'anyone', warns when one's queue is longer, and sends agent_id when picked", async () => {
    const fetchImpl = routedFetch({
      "GET /kiosk/groups/grp-1/agents": () =>
        json(200, {
          items: [
            { agent_id: "a1", name: "Busy Agent", queue_length: 4, queue_longer_than_group: true },
            { agent_id: "a2", name: "Idle Agent", queue_length: 0, queue_longer_than_group: false },
          ],
        }),
      "POST /kiosk/tickets": () => json(201, ticketResponse()),
    });
    render(<KioskFlow bootstrap={INDIVIDUAL_GROUP} client={clientWith(fetchImpl)} printer={new ResolvingPrinter()} />);

    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" }));
    expect(await screen.findByText("Choose who you'd like to see")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Anyone available" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Busy Agent.*queue is longer than usual/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Idle Agent" })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /Busy Agent/ }));

    expect(await screen.findByText("Confirm your token")).toBeInTheDocument();
    expect(screen.getByText("Agent: Busy Agent")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Get my token" }));
    await waitFor(() => expect(issuedBody(fetchImpl)).toMatchObject({ agent_id: "a1" }));
  });

  it("skips the individual level with no agent chosen when nobody on the team is on duty", async () => {
    const fetchImpl = routedFetch({
      "GET /kiosk/groups/grp-1/agents": () => json(200, { items: [] }),
      "POST /kiosk/tickets": () => json(201, ticketResponse()),
    });
    render(<KioskFlow bootstrap={INDIVIDUAL_GROUP} client={clientWith(fetchImpl)} printer={new ResolvingPrinter()} />);

    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" }));

    expect(await screen.findByText("Confirm your token")).toBeInTheDocument();
    expect(screen.queryByText("Choose who you'd like to see")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Get my token" }));
    await waitFor(() => expect(issuedBody(fetchImpl)).toEqual({ service_id: "svc-1" }));
  });
});

describe("KioskFlow custom level (ticket 26, FR-ISS-010)", () => {
  it("offers the group's configured options, can be skipped, and sends the pick's id when issuing", async () => {
    const fetchImpl = routedFetch({ "POST /kiosk/tickets": () => json(201, ticketResponse()) });
    render(<KioskFlow bootstrap={CUSTOM_LEVEL_GROUP} client={clientWith(fetchImpl)} printer={new ResolvingPrinter()} />);

    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" }));
    expect(await screen.findByRole("button", { name: "Bangla" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "English" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Skip this step" })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Bangla" }));

    expect(await screen.findByText("Confirm your token")).toBeInTheDocument();
    expect(screen.getByText("Bangla")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Get my token" }));
    await waitFor(() => expect(issuedBody(fetchImpl)).toMatchObject({ custom_level_id: "bn" }));
  });

  it("auto-picks a custom level with only one option, the same skip rule as every other level", async () => {
    const fetchImpl = routedFetch({ "POST /kiosk/tickets": () => json(201, ticketResponse()) });
    render(<KioskFlow bootstrap={SINGLE_CUSTOM_OPTION_GROUP} client={clientWith(fetchImpl)} printer={new ResolvingPrinter()} />);

    await userEvent.click(screen.getByRole("button", { name: "Tap to begin" }));

    expect(await screen.findByText("Confirm your token")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Get my token" }));
    await waitFor(() => expect(issuedBody(fetchImpl)).toMatchObject({ custom_level_id: "en" }));
  });
});
