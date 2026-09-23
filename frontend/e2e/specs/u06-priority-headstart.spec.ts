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
    // Two reception-issued tickets on the same service: a normal one first, a priority one right after. The
    // priority class's head start (seeded by the vertical profile, see support/profiles.ts) should still put it
    // ahead even though it arrived later, as long as the normal ticket's own wait stays under that head start.
    const receptionToken = await api.login(queue.reception.username, queue.reception.password);
    await api.issueTicket(receptionToken, queue.serviceId);
    const priorityTicket = await api.issueTicket(receptionToken, queue.serviceId);
    const priorityToken = priorityTicket.token_number;

    // Reprioritise it into the profile's head-start class from the live dashboard, logged in as Reception: Agent
    // carries neither `config:priority_routing` nor `ticket:issue` (PermissionMatrix.java), so `GET
    // /priority-classes` refuses it and the "Priority class" picker never has any options to select — and Agent
    // carries no `ticket:reprioritise` either. Found by actually running this against a real deployment (ticket
    // 70): the console lets Agent open the Live Dashboard and see the form render, just not use it.
    await loginConsole(page, queue.reception.username, queue.reception.password);
    await page.getByRole("button", { name: "Live Dashboard" }).click();
    // Scoped to the "Longest waits" card (LiveDashboard.tsx's own LongestWaitsCard): both "Priority class" and
    // "Reason" labels are reused verbatim by the sidebar filter and the "Set availability" card respectively
    // (ticket 70, found by actually running the suite — `getByLabel` alone is ambiguous on this page).
    const reprioritiseCard = page.locator("section").filter({ has: page.getByRole("heading", { name: "Longest waits" }) });
    await reprioritiseCard.getByLabel("Re-prioritise").selectOption({ label: priorityToken });
    // The Picker's own options load on focus, not on mount (Picker.tsx's `onFocus={onOpen}`); `selectOption` alone
    // never focuses the element first, so without this click the option list is still empty and `selectOption`
    // times out waiting for an option that will never appear (ticket 70, found by actually running the suite).
    const priorityClass = reprioritiseCard.getByLabel("Priority class");
    await priorityClass.click();
    await priorityClass.selectOption({ label: queue.profile.priorityClassWithHeadStart });
    await reprioritiseCard.getByLabel("Reason").fill("U6 UAT: priority visitor arriving into a long normal queue");
    await reprioritiseCard.getByRole("button", { name: "Change priority" }).click();

    // Call it from the counter, logged in as the Agent: opening/closing a counter session is
    // `counter:session_open_close`, which only Agent (own-scoped) or an admin role carries — Reception cannot.
    await loginConsole(page, queue.agent.username, queue.agent.password);
    await openSession(page, "E2E desk", queue.serviceName);
    await page.keyboard.press("F2");
    await expect(page.getByTestId("current-token")).toContainText(priorityToken, { timeout: 10000 });
});
