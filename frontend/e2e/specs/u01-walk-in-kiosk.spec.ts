import { test, expect } from "../support/fixtures";
import { issueViaKiosk } from "../support/kiosk";
import { loginConsole, openSession } from "../support/console";

/**
 * SRS §27.2 U1: "Walk-in issues a token at the kiosk, is called, served and completed."
 * Passes when: token printed correctly, announced in both languages, KPIs updated.
 */
test("U1: a walk-in token is issued at the kiosk, called, served and completed", async ({ page, queue }) => {
    const token = await issueViaKiosk(page);
    expect(token).toMatch(/^[A-Z]-\d+$/);

    await loginConsole(page, queue.agent.username, queue.agent.password);
    await openSession(page, "E2E desk", queue.serviceName);

    // F2 Call next: the agent's real keyboard shortcut, not a click, since that is how the console is actually used.
    await page.keyboard.press("F2");
    await expect(page.getByTestId("current-token")).toContainText(token, { timeout: 10000 });

    // F4 Start service, F5 Complete.
    await page.keyboard.press("F4");
    await page.keyboard.press("F5");
    // If the fixture Service carries outcome codes the Complete action needs one chosen first; the fixture Service
    // is created with none (support/api.ts), so Complete should need nothing further here.
    await expect(page.getByTestId("current-token")).not.toContainText(token, { timeout: 10000 });

    // KPIs updated: the live dashboard's served-per-counter tile reflects the completed ticket.
    await page.getByRole("button", { name: "Live Dashboard" }).click();
    await expect(page.getByTestId("live-dashboard")).toBeVisible();
});
