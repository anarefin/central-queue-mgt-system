import { test, expect } from "../support/fixtures";
import { loginConsole } from "../support/console";

/**
 * SRS §27.2 U9: "Supervisor re-prioritises a waiting ticket."
 * Passes when: change takes effect within 5s, reason in the audit log.
 */
test("U9: a supervisor's re-prioritisation takes effect promptly and is reasoned in the audit log", async ({ page, api, queue }) => {
    const receptionToken = await api.login(queue.reception.username, queue.reception.password);
    const ticket = await api.issueTicket(receptionToken, queue.serviceId);
    const reason = `U9 UAT ${Date.now()}`;

    // Reception, not Agent: Agent carries neither `config:priority_routing` nor `ticket:issue`, so `GET
    // /priority-classes` refuses it and the picker never has options, and Agent carries no `ticket:reprioritise`
    // either (PermissionMatrix.java) — found by actually running this against a real deployment (ticket 70).
    await loginConsole(page, queue.reception.username, queue.reception.password);
    await page.getByRole("button", { name: "Live Dashboard" }).click();

    const started = Date.now();
    // The dashboard's own priority-class picker (ticket 64, LiveDashboard.tsx's LongestWaitsCard): the ticket is a
    // plain `<select>` by token number, the priority class a `Picker` that renders as a plain `<select>` under its
    // 10-option search threshold (every vertical profile ships well under that many classes). Scoped to the
    // "Longest waits" card: "Priority class" and "Reason" are reused verbatim by the sidebar filter and the "Set
    // availability" card respectively (ticket 70, found by actually running the suite).
    const reprioritiseCard = page.locator("section").filter({ has: page.getByRole("heading", { name: "Longest waits" }) });
    await reprioritiseCard.getByLabel("Re-prioritise").selectOption({ label: ticket.token_number });
    // The Picker's own options load on focus, not on mount (Picker.tsx's `onFocus={onOpen}`); `selectOption` alone
    // never focuses the element first, so without this click the option list is still empty and `selectOption`
    // times out waiting for an option that will never appear (ticket 70, found by actually running the suite).
    const priorityClass = reprioritiseCard.getByLabel("Priority class");
    await priorityClass.click();
    await priorityClass.selectOption({ label: queue.profile.priorityClassWithHeadStart });
    await reprioritiseCard.getByLabel("Reason").fill(reason);
    await reprioritiseCard.getByRole("button", { name: "Change priority" }).click();
    // FR-QUE-012/NFR-PERF-004-adjacent: the dashboard reflects the change without a manual reload. Scoped to the
    // "longest waits" list item: an unscoped `getByText` also matches the token's own still-present `<option>` in
    // the "Re-prioritise" select above, a strict-mode violation (ticket 70, found by actually running the suite).
    await expect(reprioritiseCard.locator("li").filter({ hasText: ticket.token_number })).toBeVisible({ timeout: 5000 });
    expect(Date.now() - started).toBeLessThan(5000);

    // The reason is in the audit log (FR-SEC-040/§25.5). Read directly via GET /audit (ticket 70) — an admin-only
    // endpoint the fixture admin's own token can already call — rather than a not-yet-confirmed admin UI screen,
    // per this ticket's own note ("or GET /audit for the assertion").
    const entries = await api.auditEntries(queue.adminToken, { entity: "ticket", entity_id: ticket.id });
    expect(entries.some((entry) => entry.reason === reason)).toBe(true);
});
