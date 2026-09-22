import { test, expect } from "../support/fixtures";
import { loginConsole, openSession } from "../support/console";

/**
 * SRS §27.2 U6: "Priority visitor arrives into a long normal queue."
 * Passes when: served ahead of normal tickets that have waited less than the class head start, and no normal
 * ticket breaches its max wait. The engine-level guarantee is covered exhaustively at
 * B/queue/QueueEngineTest#aHeadStartTakesEffectOnArrivalButDoesNotOvertakeTicketsThatAlreadyWaitedLonger and
 * B/queue/QueueEngineTest#aTicketPastItsMaximumWaitIsServedBeforeEveryTicketThatIsNotEscalated (also already an
 * integration-level UAT U6 row in docs/traceability-matrix.md, ticket 09); this spec is the UI-level view of the
 * same guarantee: a supervisor watching the console sees the priority ticket called next.
 */
test("U6: a priority-class ticket is called ahead of a normal ticket that has waited less than its head start", async ({
    page,
    api,
    queue,
}) => {
    const adminToken = await api.login(process.env.QMS_BOOTSTRAP_ADMIN_USERNAME!, process.env.QMS_BOOTSTRAP_ADMIN_PASSWORD!);

    // Two reception-issued tickets on the same service: a normal one first, a priority one right after. The
    // priority class's head start (seeded by the vertical profile, see support/profiles.ts) should still put it
    // ahead even though it arrived later, as long as the normal ticket's own wait stays under that head start.
    const receptionToken = await api.login(queue.reception.username, queue.reception.password);
    await api.issueTicket(receptionToken, queue.serviceId);
    const priorityTicket = await api.issueTicket(receptionToken, queue.serviceId);
    const priorityToken = priorityTicket.token_number;

    // Reprioritise it into the profile's head-start class from the live dashboard (the visible, staff-driven way
    // this scenario actually happens — arriving already carrying a priority category would skip this step, but the
    // ordering guarantee under test is the same either way).
    await loginConsole(page, queue.agent.username, queue.agent.password);
    await page.getByRole("button", { name: "Live Dashboard" }).click();
    await page.getByLabel("Re-prioritise").selectOption({ label: priorityToken });
    await page.getByLabel("Priority class").fill(queue.profile.priorityClassWithHeadStart);
    await page.getByLabel("Reason").fill("U6 UAT: priority visitor arriving into a long normal queue");
    await page.getByRole("button", { name: "Re-prioritise" }).click();

    await page.goto("/console/");
    await openSession(page, "E2E desk", queue.serviceName);
    await page.keyboard.press("F2");
    await expect(page.getByTestId("current-token")).toContainText(priorityToken, { timeout: 10000 });
});
