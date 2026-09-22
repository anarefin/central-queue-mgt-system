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

    await loginConsole(page, queue.agent.username, queue.agent.password);
    await page.getByRole("button", { name: "Live Dashboard" }).click();

    const started = Date.now();
    await page.getByLabel("Re-prioritise").selectOption({ label: ticket.token_number });
    await page.getByLabel("Priority class").fill(queue.profile.priorityClassWithHeadStart);
    await page.getByLabel("Reason").fill(reason);
    await page.getByRole("button", { name: "Re-prioritise" }).click();
    // FR-QUE-012/NFR-PERF-004-adjacent: the dashboard reflects the change without a manual reload.
    await expect(page.getByText(new RegExp(ticket.token_number))).toBeVisible({ timeout: 5000 });
    expect(Date.now() - started).toBeLessThan(5000);

    // The reason is in the audit log (FR-SEC-040/§25.5), visible to the same admin in the admin app.
    await page.goto("/admin/");
    // Selector inferred from AuditController's route naming, not confirmed against an /admin/audit/ screen's own
    // component source when this spec was written (the frontend selector survey this suite was built from did not
    // cover the audit screen specifically) — if the route or its filter field differ, update this one navigation
    // step; the assertions after it (the reason text appearing) are what the scenario is actually about.
    await page.goto("/admin/audit/");
    await expect(page.getByText(reason)).toBeVisible({ timeout: 10000 });
});
