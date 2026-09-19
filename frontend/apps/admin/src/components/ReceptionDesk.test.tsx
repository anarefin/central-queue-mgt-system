import type { QueueSnapshot, SiteServices, Ticket } from "@qms/api-client";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Home from "../app/page";
import { json, renderApp, stubApi, type Recorded, type Routes } from "../test-utils";
import { ReceptionDesk } from "./ReceptionDesk";

const router = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

const STAMP = "2026-09-19T10:00:00+06:00";
const TOKENS = { access_token: "tok", token_type: "Bearer", expires_in: 900 };
const ME = {
  id: "u1",
  username: "sumi",
  display_name: "Sumi Akter",
  preferred_language: null as string | null,
  roles: ["reception_operator"],
  sites: ["s1"],
  groups: [],
};

const SERVICES: SiteServices = {
  site_id: "s1",
  default_language: "bn",
  items: [
    {
      id: "v1",
      name_i18n: { bn: "পরামর্শ", en: "Consultation" },
      service_group: { id: "g1", name_i18n: { bn: "বহির্বিভাগ", en: "Outpatient" } },
      token_prefix: "S",
      icon: null,
      display_order: 1,
      waiting_count: 0,
      estimated_wait_minutes: null,
    },
    {
      id: "v2",
      name_i18n: { bn: "ল্যাব" },
      service_group: { id: "g1", name_i18n: { bn: "বহির্বিভাগ", en: "Outpatient" } },
      token_prefix: "L",
      icon: null,
      display_order: 2,
      waiting_count: 3,
      estimated_wait_minutes: null,
    },
  ],
};

function ticket(over: Partial<Ticket> = {}): Ticket {
  return {
    id: "t1",
    token_number: "S-042",
    state: "waiting",
    service: { id: "v1", name_i18n: { bn: "পরামর্শ", en: "Consultation" } },
    service_group: { id: "g1", name_i18n: { bn: "বহির্বিভাগ", en: "Outpatient" } },
    site_id: "s1",
    zone: { id: "z1", name: "Ground waiting", building_label: "Block A", floor_label: "1st" },
    visit_id: "vis1",
    origin_channel: "reception",
    position: 1,
    estimated_wait_minutes: null,
    issued_at: STAMP,
    queued_at: STAMP,
    version: 0,
    secret: "s3cr3t-value",
    ...over,
  };
}

interface Desk {
  waiting: Ticket[];
  services: SiteServices;
}

/** An in-memory API: an issued ticket joins the queue and the service's waiting count. */
function fakeApi(desk: Desk, extra: Routes = {}): Recorded[] {
  return stubApi({
    "POST /auth/refresh": () => json(200, TOKENS),
    "GET /auth/me": () => json(200, ME),
    "GET /sites/s1/services?channel=reception": () => json(200, desk.services),
    "GET /queues/v1": () => {
      const snapshot: QueueSnapshot = {
        service: { id: "v1", name_i18n: { bn: "পরামর্শ", en: "Consultation" } },
        site_id: "s1",
        waiting_count: desk.waiting.length,
        estimated_wait_minutes: null,
        tickets: desk.waiting.map((w, i) => ({
          id: w.id,
          token_number: w.token_number,
          state: w.state,
          position: i + 1,
          origin_channel: w.origin_channel,
          queued_at: w.queued_at,
        })),
      };
      return json(200, snapshot);
    },
    "POST /tickets": () => {
      const issued = ticket({ id: `t${desk.waiting.length + 1}`, token_number: `S-${String(42 + desk.waiting.length).padStart(3, "0")}`, position: desk.waiting.length + 1 });
      desk.waiting.push({ ...issued, secret: undefined });
      desk.services = { ...desk.services, items: desk.services.items.map((s) => (s.id === "v1" ? { ...s, waiting_count: desk.waiting.length } : s)) };
      return json(201, issued);
    },
    ...extra,
  });
}

const fresh = (): Desk => ({ waiting: [], services: SERVICES });

beforeEach(() => router.replace.mockReset());
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function issuesOf(calls: Recorded[]): Recorded[] {
  return calls.filter((c) => c.method === "POST" && c.path === "/tickets");
}

function keyOf(call: Recorded | undefined): string | undefined {
  return (call?.init.headers as Record<string, string> | undefined)?.["Idempotency-Key"];
}

async function chooseConsultation() {
  await userEvent.click(await screen.findByRole("radio", { name: /Consultation/ }));
}

describe("reception desk (SRS §8.3)", () => {
  it("lists the services reception can issue, with each queue's length, in the reader's language", async () => {
    fakeApi(fresh());
    renderApp(<ReceptionDesk />);

    const consultation = await screen.findByRole("radio", { name: /Consultation/ });
    expect(consultation.closest("label")).toHaveTextContent("Outpatient");
    expect(consultation.closest("label")).toHaveTextContent("0 waiting");
    // No English name for the second service: the site's default language stands in (FR-I18N-011).
    expect(screen.getByRole("radio", { name: /ল্যাব/ }).closest("label")).toHaveTextContent("3 waiting");
    expect(screen.getByRole("button", { name: "Issue ticket" })).toBeDisabled();
    expect(screen.getByText("Choose a service to see its queue.")).toBeInTheDocument();
  });

  it("issues a walk-in ticket and shows token, service, waiting area, position, estimate and secret, then the queue", async () => {
    const calls = fakeApi(fresh());
    renderApp(<ReceptionDesk />);
    await chooseConsultation();
    expect(await screen.findByText("Nobody is waiting for this service.")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Issue ticket" }));

    const result = (await screen.findByText("Ticket issued")).closest("section")!;
    expect(within(result).getByTestId("issued-token")).toHaveTextContent("S-042");
    expect(within(result).getByText("Service: Consultation")).toBeInTheDocument();
    expect(within(result).getByText("Waiting area: Ground waiting, Block A, floor 1st")).toBeInTheDocument();
    expect(within(result).getByText("Position in queue: 1")).toBeInTheDocument();
    expect(within(result).getByText("Estimated wait: not available yet")).toBeInTheDocument();
    expect(within(result).getByText("Ticket secret: s3cr3t-value")).toBeInTheDocument();
    expect(within(result).getByText(/Give it to the visitor/)).toBeInTheDocument();

    const issue = issuesOf(calls);
    expect(issue).toHaveLength(1);
    expect(JSON.parse(String(issue[0]!.init.body))).toMatchObject({ service_id: "v1", origin_channel: "reception" });
    expect(JSON.parse(String(issue[0]!.init.body)).occurred_at).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(keyOf(issue[0])).toMatch(/^[0-9a-f-]{36}$/);
    expect((issue[0]!.init.headers as Record<string, string>).Authorization).toBe("Bearer tok");

    // The queue and the service list reflect the new ticket without a reload.
    const queue = (await screen.findByText("1 waiting", { selector: "p" })).closest("section")!;
    expect(within(queue).getByText("S-042")).toBeInTheDocument();
    expect(within(queue).getByText("Waiting · position 1")).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /Consultation/ }).closest("label")).toHaveTextContent("1 waiting");
  });

  it("says where to wait without a building", async () => {
    fakeApi(fresh(), {
      "POST /tickets": () => json(201, ticket({ zone: { id: "z1", name: "Hall", building_label: null, floor_label: "2nd" } })),
    });
    renderApp(<ReceptionDesk />);
    await chooseConsultation();
    await userEvent.click(screen.getByRole("button", { name: "Issue ticket" }));

    expect(await screen.findByText("Waiting area: Hall, floor 2nd")).toBeInTheDocument();
  });

  it("says so when no counter serves the service yet, and leaves out a position the ticket does not have", async () => {
    fakeApi(fresh(), { "POST /tickets": () => json(201, ticket({ zone: null, position: null })) });
    renderApp(<ReceptionDesk />);
    await chooseConsultation();
    await userEvent.click(screen.getByRole("button", { name: "Issue ticket" }));

    expect(await screen.findByText("Waiting area: not set yet")).toBeInTheDocument();
    expect(screen.queryByText(/Position in queue/)).not.toBeInTheDocument();
  });

  it("keeps one idempotency key across a retry after a lost response, and uses a new one for the next ticket", async () => {
    let answered = false;
    const desk = fresh();
    const calls = fakeApi(desk, {
      "POST /tickets": () => {
        if (!answered) {
          answered = true;
          throw new TypeError("connection lost");
        }
        return json(201, ticket());
      },
    });
    renderApp(<ReceptionDesk />);
    await chooseConsultation();

    await userEvent.click(screen.getByRole("button", { name: "Issue ticket" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not reach the server");
    await userEvent.click(screen.getByRole("button", { name: "Issue ticket" }));
    await screen.findByText("Ticket issued");
    await userEvent.click(screen.getByRole("button", { name: "Issue ticket" }));

    await waitFor(() => expect(issuesOf(calls)).toHaveLength(3));
    const [first, retry, next] = issuesOf(calls).map(keyOf);
    expect(retry).toBe(first);
    expect(next).not.toBe(first);
  });

  it("explains a refusal in words and uses a new key for the next attempt", async () => {
    const calls = fakeApi(fresh(), {
      "POST /tickets": () =>
        json(409, { error: { code: "conflict", message: "x", details: { reason: "service_inactive" }, trace_id: "t" } }),
    });
    renderApp(<ReceptionDesk />);
    await chooseConsultation();

    await userEvent.click(screen.getByRole("button", { name: "Issue ticket" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("This service is not open for tickets right now.");
    expect(screen.queryByText("Ticket issued")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Issue ticket" }));

    await waitFor(() => expect(issuesOf(calls)).toHaveLength(2));
    expect(keyOf(issuesOf(calls)[1])).not.toBe(keyOf(issuesOf(calls)[0]));
  });

  it("shows Bangla labels and keeps the token number in Western Arabic digits (FR-I18N-020, §27.5)", async () => {
    fakeApi(fresh());
    renderApp(<ReceptionDesk />, ["bn-BD"]);

    await userEvent.click(await screen.findByRole("radio", { name: /পরামর্শ/ }));
    await userEvent.click(screen.getByRole("button", { name: "টিকিট দিন" }));

    const result = (await screen.findByText("টিকিট দেওয়া হয়েছে")).closest("section")!;
    expect(within(result).getByTestId("issued-token")).toHaveTextContent("S-042");
    expect(within(result).getByText("সেবা: পরামর্শ")).toBeInTheDocument();
    expect(within(result).getByText("অপেক্ষার জায়গা: Ground waiting, Block A, 1st তলা")).toBeInTheDocument();
    expect(within(result).getByText("আনুমানিক অপেক্ষা: এখনও পাওয়া যাচ্ছে না")).toBeInTheDocument();
  });

  it("tells a user with no site that there is nothing to issue for, and asks nothing of the API", async () => {
    const calls = fakeApi(fresh(), { "GET /auth/me": () => json(200, { ...ME, sites: [] }) });
    renderApp(<ReceptionDesk />);

    expect(await screen.findByText(/not assigned to a site/)).toBeInTheDocument();
    expect(calls.filter((c) => c.path.startsWith("/sites"))).toHaveLength(0);
  });

  it("links to the desk from the home screen for a Reception Operator only; the API enforces access either way", async () => {
    const health = { "GET /health/dependencies": () => json(200, { status: "up", dependencies: {} }) };
    fakeApi(fresh(), health);
    const reception = renderApp(<Home />);
    const link = await screen.findByRole("link", { name: "Reception desk" });
    expect(link.getAttribute("href")).toMatch(/^\/reception\/?$/);
    reception.unmount();

    fakeApi(fresh(), { ...health, "GET /auth/me": () => json(200, { ...ME, roles: ["agent"] }) });
    renderApp(<Home />);
    await screen.findByText("Agent");
    expect(screen.queryByRole("link", { name: "Reception desk" })).not.toBeInTheDocument();
  });
});
