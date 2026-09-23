import { test, expect } from "../support/fixtures";
import { issueViaKiosk } from "../support/kiosk";
import { loginConsole, openSession } from "../support/console";

/**
 * SRS §27.2 U1: "Walk-in issues a token at the kiosk, is called, served and completed."
 * Passes when: token printed correctly, announced in both languages, KPIs updated.
 */
test("U1: a walk-in token is issued at the kiosk, called, served and completed", async ({ page, api, queue }) => {
    const token = await issueViaKiosk(page, api, queue.adminToken, queue.siteId, queue.serviceName);
    expect(token).toMatch(/^[A-Z]-\d+$/);

    await loginConsole(page, queue.agent.username, queue.agent.password);
    await openSession(page, "E2E desk", queue.serviceName);

    // F2 Call next: the agent's real keyboard shortcut, not a click, since that is how the console is actually used.
    await page.keyboard.press("F2");
    await expect(page.getByTestId("current-token")).toContainText(token, { timeout: 10000 });

    // F4 Start service, F5 Complete. `complete()` only acts once `ticket.state === "serving"` (CounterConsole.tsx's
    // own `canComplete` gate) — pressing F5 immediately after F4 races the still-in-flight start request and
    // silently no-ops, so this waits for the "In service" badge first (ticket 70, found by actually running the
    // suite: F5 was firing before the server had applied F4 at all).
    await page.keyboard.press("F4");
    await expect(page.getByText("In service")).toBeVisible({ timeout: 10000 });
    await page.keyboard.press("F5");
    // The seeded starter Service carries no outcome codes, so Complete needs nothing further here. Completing
    // unmounts `current-token` entirely (ServingDesk.tsx only renders it inside `{ticket && ...}`) rather than
    // leaving it present with different text, so `.not.toContainText` — which Playwright treats as a failure once
    // the element is gone, not a pass — is the wrong matcher; `.not.toBeVisible` treats "gone" as satisfying "not
    // visible" (ticket 70, found by actually running the suite: Complete WAS succeeding, this assertion was wrong).
    await expect(page.getByTestId("current-token")).not.toBeVisible({ timeout: 10000 });

    // KPIs updated: the live dashboard's served-per-counter tile reflects the completed ticket.
    await page.getByRole("button", { name: "Live Dashboard" }).click();
    await expect(page.getByTestId("live-dashboard")).toBeVisible();
});
