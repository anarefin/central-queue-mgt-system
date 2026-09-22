import { Page, expect } from "@playwright/test";

/**
 * Drives the kiosk common path (ticket 25/26) from the idle screen to a printed/issued token, tolerating the
 * screens a one-group/one-service fixture Queue auto-skips (NFR-USA-001's "fully skips" path) and the ones an
 * unconfigured Service leaves out (identify, individual-agent, custom-level) — the same shape KioskFlow.test.tsx
 * drives, just against the real deployed app instead of a component harness.
 */
export async function issueViaKiosk(page: Page, basePath = "/kiosk/"): Promise<string> {
    await page.goto(basePath);

    // Idle: one language button per configured language, or "Tap to begin" when only one is configured.
    const tapToBegin = page.getByRole("button", { name: "Tap to begin" });
    if (await tapToBegin.isVisible().catch(() => false)) {
        await tapToBegin.click();
    } else {
        await page.getByRole("button", { name: "English" }).click();
    }

    // Group/Service screens are skipped automatically when the fixture Queue has exactly one of each.
    const identifyHeading = page.getByRole("heading", { name: "Identify yourself" });
    if (await identifyHeading.isVisible({ timeout: 3000 }).catch(() => false)) {
        const skip = page.getByRole("button", { name: "Continue without identification" });
        if (await skip.isVisible().catch(() => false)) await skip.click();
    }

    // Confirm screen.
    await page.getByRole("button", { name: "Get my token" }).click();

    // Result: either the printed-token screen or the print-failure QR fallback (see forcePrintFailure below).
    await expect(page.getByText(/Take your ticket from the printer|your token is saved/)).toBeVisible({ timeout: 10000 });

    const tokenText = await page.locator("main").innerText();
    const match = tokenText.match(/[A-Z]-\d{3,}/);
    if (!match) throw new Error(`Could not find a token number on the kiosk result screen: ${tokenText}`);
    return match[0];
}

/** Forces the kiosk's real print call to fail, so the U8 "printer runs out of paper" fallback is reachable at all
 * (a real headless browser's window.print() succeeds silently; production only reaches this screen on a genuine
 * printer error, per the frontend research this suite was written from). Must run before page.goto(). */
export async function forcePrintFailure(page: Page): Promise<void> {
    await page.addInitScript(() => {
        window.print = () => {
            throw new Error("E2E: simulated printer failure (U8)");
        };
    });
}
