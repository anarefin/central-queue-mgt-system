import type { PriorityClass, QueueSnapshot, SiteServices, Ticket } from "@qms/api-client";
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
      estimated_wait_minutes: { low: 0, high: 5 },
    },
    {
      id: "v2",
      name_i18n: { bn: "ল্যাব" },
      service_group: { id: "g1", name_i18n: { bn: "বহির্বিভাগ", en: "Outpatient" } },
      token_prefix: "L",
      icon: null,
      display_order: 2,
      waiting_count: 3,
      estimated_wait_minutes: { low: 30, high: 35 },
    },
  ],
};

function priorityClass(over: Partial<PriorityClass>): PriorityClass {
  return {
    id: "c0",
    name_i18n: { en: "Normal", bn: "সাধারণ" },
    headstart_minutes: 0,
    max_wait_minutes: null,
    token_prefix_override: null,
    is_default: false,
    active: true,
    created_at: STAMP,
    updated_at: STAMP,
    ...over,
  };
}

const CLASSES: PriorityClass[] = [
  priorityClass({ is_default: true }),
  priorityClass({ id: "c1", name_i18n: { en: "Senior citizen", bn: "বয়স্ক নাগরিক" }, headstart_minutes: 20 }),
  priorityClass({ id: "c2", name_i18n: { en: "Retired class" }, headstart_minutes: 90, active: false }),
];

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
    priority_class: { id: "c0", name_i18n: { bn: "সাধারণ", en: "Normal" } },
    position: 1,
    estimated_wait_minutes: { low: 15, high: 20 },
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
  classes: PriorityClass[];
}

/** An in-memory API: an issued ticket joins the queue and the service's waiting count. */
function fakeApi(desk: Desk, extra: Routes = {}): Recorded[] {
  return stubApi({
    "POST /auth/refresh": () => json(200, TOKENS),
    "GET /auth/me": () => json(200, ME),
    "GET /sites/s1/services?channel=reception": () => json(200, desk.services),
    "GET /priority-classes": () => json(200, { items: desk.classes }),
    "GET /queues/v1": () => {
      const snapshot: QueueSnapshot = {
        service: { id: "v1", name_i18n: { bn: "পরামর্শ", en: "Consultation" } },
        site_id: "s1",
        waiting_count: desk.waiting.length,
        // A ticket issued now would have everyone waiting in front of it, at about ten minutes each (FR-QUE-040).
        estimated_wait_minutes: { low: desk.waiting.length * 10, high: desk.waiting.length * 10 + 5 },
        tickets: desk.waiting.map((w, i) => ({
          id: w.id,
          token_number: w.token_number,
          state: w.state,
          position: i + 1,
          origin_channel: w.origin_channel,
          queued_at: w.queued_at,
          priority_class: w.priority_class,
          escalated: false,
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

const fresh = (): Desk => ({ waiting: [], services: SERVICES, classes: CLASSES });

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
    expect(consultation.closest("label")).toHaveTextContent("0 waiting · about 0–5 min");
    // No English name for the second service: the site's default language stands in (FR-I18N-011).
    expect(screen.getByRole("radio", { name: /ল্যাব/ }).closest("label")).toHaveTextContent("3 waiting · about 30–35 min");
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
    // A rounded range, never one figure that reads as a promise (FR-QUE-042, FR-ISS-005).
    expect(within(result).getByText("Estimated wait: about 15–20 minutes")).toBeInTheDocument();
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

  it("lets reception give the ticket a priority class, which is sent to the API and shown on the result (FR-QUE-011)", async () => {
    const calls = fakeApi(fresh(), {
      "POST /tickets": (init) => {
        const chosen = JSON.parse(String(init.body)).priority_class_id as string | undefined;
        const name = chosen === "c1" ? { en: "Senior citizen", bn: "বয়স্ক নাগরিক" } : { en: "Normal", bn: "সাধারণ" };
        return json(201, ticket({ position: chosen ? 1 : 4, priority_class: { id: chosen ?? "c0", name_i18n: name } }));
      },
    });
    renderApp(<ReceptionDesk />);
    await chooseConsultation();

    const select = await screen.findByLabelText("Priority class");
    expect(within(select).getAllByRole("option").map((o) => o.textContent)).toEqual(["Normal", "Senior citizen (head start 20 min)"]);
    expect(select).toHaveValue("");
    await userEvent.selectOptions(select, "Senior citizen (head start 20 min)");
    await userEvent.click(screen.getByRole("button", { name: "Issue ticket" }));

    const result = (await screen.findByText("Ticket issued")).closest("section")!;
    expect(within(result).getByText("Priority class: Senior citizen")).toBeInTheDocument();
    expect(JSON.parse(String(issuesOf(calls)[0]!.init.body))).toMatchObject({ service_id: "v1", priority_class_id: "c1" });
  });

  it("sends no class when reception leaves the default, and uses a new key when the class changes after an unknown outcome", async () => {
    let attempts = 0;
    const calls = fakeApi(fresh(), {
      "POST /tickets": () => {
        attempts += 1;
        if (attempts === 1) throw new TypeError("connection lost");
        return json(201, ticket());
      },
    });
    renderApp(<ReceptionDesk />);
    await chooseConsultation();
    await userEvent.selectOptions(await screen.findByLabelText("Priority class"), "Senior citizen (head start 20 min)");
    await userEvent.click(screen.getByRole("button", { name: "Issue ticket" }));
    await screen.findByRole("alert");
    await userEvent.selectOptions(screen.getByLabelText("Priority class"), "Normal");
    await userEvent.click(screen.getByRole("button", { name: "Issue ticket" }));

    await waitFor(() => expect(issuesOf(calls)).toHaveLength(2));
    const [first, second] = issuesOf(calls);
    expect(JSON.parse(String(first!.init.body)).priority_class_id).toBe("c1");
    expect(JSON.parse(String(second!.init.body))).not.toHaveProperty("priority_class_id");
    expect(keyOf(second)).not.toBe(keyOf(first));
  });

  it("names a waiting ticket's priority class and flags one past its maximum wait, but not the default class", async () => {
    const desk = fresh();
    desk.waiting = [
      { ...ticket({ id: "w1", token_number: "S-001", secret: undefined }), priority_class: { id: "c0", name_i18n: { en: "Normal", bn: "সাধারণ" } } },
      { ...ticket({ id: "w2", token_number: "S-002", secret: undefined }), priority_class: { id: "c1", name_i18n: { en: "Senior citizen", bn: "বয়স্ক নাগরিক" } } },
    ];
    fakeApi(desk, {
      "GET /queues/v1": () =>
        json(200, {
          service: { id: "v1", name_i18n: { en: "Consultation" } },
          site_id: "s1",
          waiting_count: 2,
          estimated_wait_minutes: null,
          tickets: desk.waiting.map((w, i) => ({
            id: w.id,
            token_number: w.token_number,
            state: w.state,
            position: i + 1,
            origin_channel: "reception",
            queued_at: w.queued_at,
            priority_class: w.priority_class,
            escalated: w.id === "w1",
          })),
        }),
    });
    renderApp(<ReceptionDesk />);
    await chooseConsultation();

    const queue = (await screen.findByText("2 waiting", { selector: "p" })).closest("section")!;
    const [normal, senior] = within(queue).getAllByRole("listitem");
    expect(normal).toHaveTextContent("S-001");
    expect(normal).not.toHaveTextContent("Normal");
    expect(normal).toHaveTextContent("past its maximum wait");
    expect(senior).toHaveTextContent("Senior citizen");
    expect(senior).not.toHaveTextContent("past its maximum wait");
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

  it("shows the estimate of the queue as a range for a ticket issued now, and follows it when the queue is read again (FR-QUE-042)", async () => {
    fakeApi(fresh());
    renderApp(<ReceptionDesk />);
    await chooseConsultation();
    expect(await screen.findByText("A ticket issued now: about 0–5 minutes")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Issue ticket" }));

    expect(await screen.findByText("A ticket issued now: about 10–15 minutes")).toBeInTheDocument();
  });

  it("never turns the range into one figure, and says so when a ticket has no estimate because it left the queue", async () => {
    fakeApi(fresh(), { "POST /tickets": () => json(201, ticket({ position: null, estimated_wait_minutes: null })) });
    renderApp(<ReceptionDesk />);
    await chooseConsultation();
    await userEvent.click(screen.getByRole("button", { name: "Issue ticket" }));

    const result = (await screen.findByText("Ticket issued")).closest("section")!;
    expect(within(result).getByText("Estimated wait: not available")).toBeInTheDocument();
    expect(within(result).queryByText(/about/)).not.toBeInTheDocument();
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
    expect(within(result).getByText("আনুমানিক অপেক্ষা: প্রায় ১৫–২০ মিনিট")).toBeInTheDocument();
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

describe("change of priority and cancel from the queue (FR-QUE-012, §5.2)", () => {
  const waitingTicket = (id: string, token: string, klass = { id: "c0", name_i18n: { bn: "সাধারণ", en: "Normal" } }): Ticket => ({
    ...ticket({ id, token_number: token, priority_class: klass }),
    secret: undefined,
  });
  const twoWaiting = (): Desk => ({ ...fresh(), waiting: [waitingTicket("t1", "S-042"), waitingTicket("t2", "S-043")] });

  const priorityCalls = (calls: Recorded[]) => calls.filter((c) => c.method === "POST" && c.path.endsWith("/priority"));
  const cancelCalls = (calls: Recorded[]) => calls.filter((c) => c.method === "POST" && c.path.endsWith("/cancel"));
  const queueReads = (calls: Recorded[]) => calls.filter((c) => c.path === "/queues/v1");

  it("changes a waiting ticket's class with a reason, sends both to the API and reads the queue again", async () => {
    const desk = twoWaiting();
    const calls = fakeApi(desk, {
      "POST /tickets/t2/priority": (init) => {
        const input = JSON.parse(String(init.body)) as { priority_class_id: string };
        expect(input.priority_class_id).toBe("c1");
        // The queue is ordered on every read: the next snapshot has the ticket first.
        desk.waiting = [{ ...desk.waiting[1]!, priority_class: { id: "c1", name_i18n: { en: "Senior citizen", bn: "বয়স্ক নাগরিক" } } }, desk.waiting[0]!];
        return json(200, { id: "t2", token_number: "S-043", state: "waiting", priority_class_id: "c1", position: 1, version: 1 });
      },
    });
    renderApp(<ReceptionDesk />);
    await chooseConsultation();
    await userEvent.click(await screen.findByRole("button", { name: "Change priority of S-043" }));

    const form = screen.getByRole("form", { name: "Change priority of S-043" });
    const select = within(form).getByLabelText("New priority class");
    expect(within(select).getAllByRole("option").map((o) => o.textContent)).toEqual(["Senior citizen (head start 20 min)"]);
    await userEvent.type(within(form).getByLabelText("Reason (kept in the audit log)"), "  Elderly visitor, long wait  ");
    const before = queueReads(calls).length;
    await userEvent.click(within(form).getByRole("button", { name: "Change priority" }));

    expect(await screen.findByText("S-043 now queues as Senior citizen, position 1.")).toBeInTheDocument();
    expect(priorityCalls(calls)).toHaveLength(1);
    expect(priorityCalls(calls)[0]!.path).toBe("/tickets/t2/priority");
    expect(JSON.parse(String(priorityCalls(calls)[0]!.init.body))).toEqual({ priority_class_id: "c1", reason: "Elderly visitor, long wait" });
    await waitFor(() => expect(queueReads(calls).length).toBeGreaterThan(before));
    const list = screen.getByRole("list", { name: "Queue for Consultation" });
    expect(within(list).getAllByRole("listitem").map((li) => li.querySelector("strong")?.textContent)).toEqual(["S-043", "S-042"]);
    expect(screen.queryByRole("form", { name: "Change priority of S-043" })).not.toBeInTheDocument();
  });

  it("asks for a reason before it asks the API, and never offers a class the ticket already has or a switched-off one", async () => {
    const calls = fakeApi(twoWaiting());
    renderApp(<ReceptionDesk />);
    await chooseConsultation();
    await userEvent.click(await screen.findByRole("button", { name: "Change priority of S-042" }));

    const form = screen.getByRole("form", { name: "Change priority of S-042" });
    expect(within(form).queryByRole("option", { name: "Retired class (head start 90 min)" })).not.toBeInTheDocument();
    await userEvent.click(within(form).getByRole("button", { name: "Change priority" }));

    expect(await within(form).findByRole("alert")).toHaveTextContent("Give a reason.");
    expect(priorityCalls(calls)).toHaveLength(0);
  });

  it("offers the normal class to move a ticket back, by its plain name", async () => {
    const senior = { id: "c1", name_i18n: { en: "Senior citizen", bn: "বয়স্ক নাগরিক" } };
    fakeApi({ ...fresh(), waiting: [waitingTicket("t1", "S-042", senior)] });
    renderApp(<ReceptionDesk />);
    await chooseConsultation();
    await userEvent.click(await screen.findByRole("button", { name: "Change priority of S-042" }));

    const select = within(screen.getByRole("form", { name: "Change priority of S-042" })).getByLabelText("New priority class");
    expect(within(select).getAllByRole("option").map((o) => o.textContent)).toEqual(["Normal"]);
  });

  it("says in words why the API refused a change and leaves the panel open", async () => {
    const calls = fakeApi(twoWaiting(), {
      "POST /tickets/t1/priority": () => json(409, { error: { code: "conflict", message: "x", details: { reason: "ticket_not_waiting" }, trace_id: "t" } }),
    });
    renderApp(<ReceptionDesk />);
    await chooseConsultation();
    await userEvent.click(await screen.findByRole("button", { name: "Change priority of S-042" }));
    const form = screen.getByRole("form", { name: "Change priority of S-042" });
    await userEvent.type(within(form).getByLabelText("Reason (kept in the audit log)"), "Urgent");
    await userEvent.click(within(form).getByRole("button", { name: "Change priority" }));

    expect(await within(form).findByRole("alert")).toHaveTextContent("This ticket is no longer waiting, so its class cannot be changed.");
    expect(priorityCalls(calls)).toHaveLength(1);
    await userEvent.click(within(form).getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("form", { name: "Change priority of S-042" })).not.toBeInTheDocument();
  });

  it("cancels a ticket with an optional reason and reads the queue again", async () => {
    const desk = twoWaiting();
    const calls = fakeApi(desk, {
      "POST /tickets/t1/cancel": () => {
        desk.waiting = desk.waiting.slice(1);
        return json(200, { id: "t1", token_number: "S-042", state: "cancelled", priority_class_id: "c0", position: null, version: 1 });
      },
    });
    renderApp(<ReceptionDesk />);
    await chooseConsultation();
    await userEvent.click(await screen.findByRole("button", { name: "Cancel S-042" }));

    const form = screen.getByRole("form", { name: "Cancel S-042?" });
    await userEvent.type(within(form).getByLabelText("Reason (optional)"), "Visitor left");
    await userEvent.click(within(form).getByRole("button", { name: "Cancel this ticket" }));

    expect(await screen.findByText("S-042 was cancelled.")).toBeInTheDocument();
    expect(cancelCalls(calls)).toHaveLength(1);
    expect(JSON.parse(String(cancelCalls(calls)[0]!.init.body))).toEqual({ reason: "Visitor left" });
    await waitFor(() => expect(screen.queryByRole("button", { name: "Cancel S-042" })).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Cancel S-043" })).toBeInTheDocument();
  });

  it("cancels without a reason, and says when the ticket has already closed", async () => {
    const calls = fakeApi(twoWaiting(), {
      "POST /tickets/t2/cancel": () => json(409, { error: { code: "conflict", message: "x", details: { reason: "ticket_not_active" }, trace_id: "t" } }),
    });
    renderApp(<ReceptionDesk />);
    await chooseConsultation();
    await userEvent.click(await screen.findByRole("button", { name: "Cancel S-043" }));
    const form = screen.getByRole("form", { name: "Cancel S-043?" });
    await userEvent.click(within(form).getByRole("button", { name: "Cancel this ticket" }));

    expect(await within(form).findByRole("alert")).toHaveTextContent("This ticket has already closed.");
    expect(cancelCalls(calls)[0]!.init.body).toBeUndefined();
  });

  it("shows the actions in Bangla with the token number in Western Arabic digits (FR-I18N-020, §27.5)", async () => {
    fakeApi(twoWaiting());
    renderApp(<ReceptionDesk />, ["bn-BD"]);
    await userEvent.click(await screen.findByRole("radio", { name: /পরামর্শ/ }));

    await userEvent.click(await screen.findByRole("button", { name: "S-042-এর অগ্রাধিকার বদলান" }));
    const form = screen.getByRole("form", { name: "S-042-এর অগ্রাধিকার বদলান" });
    expect(within(form).getByLabelText("নতুন অগ্রাধিকার শ্রেণি")).toBeInTheDocument();
    expect(within(form).getByLabelText("কারণ (নিরীক্ষা লগে সংরক্ষিত থাকবে)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "S-043 বাতিল করুন" })).toBeInTheDocument();
  });
});
