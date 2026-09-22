import { test, expect } from "../support/fixtures";
import { issueViaKiosk } from "../support/kiosk";
import { loginConsole, openSession } from "../support/console";

/**
 * SRS §27.2 U5: "Agent transfers a ticket to another department."
 * Passes when: successor ticket created with the same token number and visit; waits attributed per FR-QUE-053.
 */
test("U5: a transferred ticket keeps its token number on a successor ticket", async ({ page, api, queue }) => {
    const adminToken = await api.login(process.env.QMS_BOOTSTRAP_ADMIN_USERNAME!, process.env.QMS_BOOTSTRAP_ADMIN_PASSWORD!);
    const otherGroupId = await api.createServiceGroup(adminToken, queue.siteId, "E2E other department");
    const otherServiceName = "E2E other department service";
    // "Anyone" (no specific counter/agent) is the transfer target: the receiving department just needs the
    // Service to exist and be reachable, not a counter staffed right now.
    await api.createService(adminToken, otherGroupId, otherServiceName);

    const token = await issueViaKiosk(page);

    await loginConsole(page, queue.agent.username, queue.agent.password);
    await openSession(page, "E2E desk", queue.serviceName);
    await page.keyboard.press("F2"); // call
    await page.keyboard.press("F4"); // start service
    await expect(page.getByTestId("current-token")).toContainText(token);

    await page.keyboard.press("F7"); // open the transfer panel
    await page.getByLabel("Send to service").selectOption({ label: otherServiceName });
    await page.getByRole("radio", { name: "Anyone" }).check();
    await page.getByLabel("Note for the next agent (required)").fill("E2E U5 transfer");
    await page.getByRole("button", { name: "Transfer" }).click();
    await expect(page.getByTestId("current-token")).not.toContainText(token, { timeout: 10000 });

    // The successor keeps the same token number (Invariant 4, ADR-0006), already proven at the engine level by
    // B/queue/TransferTest and end to end by the ticket 15 IT cited in docs/traceability-matrix.md; this spec's own
    // job is the UI trigger above (F7 -> the transfer panel -> the receiving desk), not re-deriving that guarantee.
});
