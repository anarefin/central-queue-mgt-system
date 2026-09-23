import { test, expect } from "../support/fixtures";
import { issueViaKiosk } from "../support/kiosk";
import { loginConsole, openSession } from "../support/console";

/**
 * SRS §27.2 U5: "Agent transfers a ticket to another department."
 * Passes when: successor ticket created with the same token number and visit; waits attributed per FR-QUE-053.
 */
test("U5: a transferred ticket keeps its token number on a successor ticket", async ({ page, api, queue }) => {
    const token = await issueViaKiosk(page, api, queue.adminToken, queue.siteId, queue.serviceName);

    // Created only now, after the kiosk has already issued its token: the fixture's own seeded catalogue (ticket
    // 67) is exactly one Service group, and the kiosk's Group screen auto-skips only while that stays true — a
    // second group created before `issueViaKiosk` makes the kiosk show a Group picker `issueViaKiosk` does not
    // handle, and the whole flow times out waiting for a screen it will never reach (ticket 70, found by actually
    // running the suite).
    const adminToken = await api.login(process.env.QMS_BOOTSTRAP_ADMIN_USERNAME!, process.env.QMS_BOOTSTRAP_ADMIN_PASSWORD!);
    const otherGroupId = await api.createServiceGroup(adminToken, queue.siteId, "E2E other department");
    const otherServiceName = "E2E other department service";
    // "Anyone" (no specific counter/agent) is the transfer target: the receiving department just needs the
    // Service to exist and be reachable, not a counter staffed right now.
    await api.createService(adminToken, otherGroupId, otherServiceName);

    await loginConsole(page, queue.agent.username, queue.agent.password);
    await openSession(page, "E2E desk", queue.serviceName);
    await page.keyboard.press("F2"); // call
    await expect(page.getByTestId("current-token")).toContainText(token);
    // `canTransfer` (CounterConsole.tsx) only opens once `ticket.state === "serving"`; F7 right after F4 races the
    // still-in-flight start request the same way F5 does in U1 (ticket 70, found by actually running the suite).
    await page.keyboard.press("F4"); // start service
    await expect(page.getByText("In service")).toBeVisible({ timeout: 10000 });

    await page.keyboard.press("F7"); // open the transfer panel
    await page.getByLabel("Send to service").selectOption({ label: otherServiceName });
    await page.getByRole("radio", { name: "Anyone" }).check();
    // The note field's own label is terminology-remapped ("Note for the next {agent} (required)", ticket 69) —
    // banking renders {agent} as "Officer", not "agent" — so this targets the field's stable DOM id instead of the
    // translated label text (ticket 70, found by actually running the suite).
    await page.locator("#transfer-note").fill("E2E U5 transfer");
    // Exact: the panel's own "Transfer F7" toolbar button (still on screen behind the side panel) also matches
    // "Transfer" as a substring, a strict-mode violation (ticket 70, found by actually running the suite).
    await page.getByRole("button", { name: "Transfer", exact: true }).click();
    // Transferring unmounts `current-token` (ServingDesk.tsx only renders it inside `{ticket && ...}`) rather than
    // leaving it present with different text; `.not.toBeVisible` treats "gone" as satisfying "not visible", unlike
    // `.not.toContainText` which fails once the element is gone (ticket 70, found by actually running the suite).
    await expect(page.getByTestId("current-token")).not.toBeVisible({ timeout: 10000 });

    // The successor keeps the same token number (Invariant 4, ADR-0006), already proven at the engine level by
    // B/queue/TransferTest and end to end by the ticket 15 IT cited in docs/traceability-matrix.md; this spec's own
    // job is the UI trigger above (F7 -> the transfer panel -> the receiving desk), not re-deriving that guarantee.
});
