import { Page, expect } from "@playwright/test";
import { Api } from "./api";
import { pairDevice } from "./device";

/**
 * Drives the kiosk common path (ticket 25/26) from an unpaired device to a printed/issued token, tolerating the
 * screens a one-group/one-service fixture Queue auto-skips (NFR-USA-001's "fully skips" path) and the ones an
 * unconfigured Service leaves out (identify, individual-agent, custom-level) — the same shape KioskFlow.test.tsx
 * drives, just against the real deployed app instead of a component harness.
 *
 * Pairs the device itself first (ticket 70: a fresh Playwright page has its own isolated storage, so nothing is
 * paired yet, and a real kiosk shows nothing else until it is).
 */
export async function issueViaKiosk(page: Page, api: Api, adminToken: string, siteId: string, serviceName: string, basePath = "/kiosk/"): Promise<string> {
    await pairDevice(page, api, adminToken, "kiosk", siteId, basePath);

    // Idle: one language button per configured language, or "Tap to begin" when only one is configured.
    const tapToBegin = page.getByRole("button", { name: "Tap to begin" });
    if (await tapToBegin.isVisible().catch(() => false)) {
        await tapToBegin.click();
    } else {
        await page.getByRole("button", { name: "English" }).click();
    }

    // The Group screen is skipped automatically (the seeded starter catalogue, ticket 67, is always exactly one
    // Service group); the Service screen is not — that same seed creates every one of the profile's starter
    // Services under that one group (ticket 70: the fixture no longer hand-creates a single Service), so the kiosk
    // always shows a pick here now.
    const serviceHeading = page.getByRole("heading", { name: "Choose a service" });
    if (await serviceHeading.isVisible({ timeout: 3000 }).catch(() => false)) {
        await page.getByRole("button", { name: serviceName, exact: true }).click();
    }

    const identifyHeading = page.getByRole("heading", { name: "Identify yourself" });
    if (await identifyHeading.isVisible({ timeout: 3000 }).catch(() => false)) {
        const skip = page.getByRole("button", { name: "Continue without identification" });
        if (await skip.isVisible().catch(() => false)) await skip.click();
    }

    // Confirm screen. The label is terminology-remapped and language-dependent ("Get my {ticket}", ticket 69); the
    // testid is stable across every profile and language (ticket 70).
    await page.getByTestId("kiosk-confirm-print").click();

    // Result: either the printed-token screen or the print-failure QR fallback (see forcePrintFailure below).
    // "Take your {ticket} from the printer" / "...your {ticket} is saved" (kiosk.result.*Title, en.json): {ticket}
    // is the profile's own terminology-remapped label ("Token"), and the regex form of getByText is case-sensitive
    // unlike its string form — found by actually running the suite (ticket 70; the previous regex said "ticket"
    // instead of "token" and never matched).
    await expect(page.getByText(/Take your Token from the printer|your Token is saved/i)).toBeVisible({ timeout: 10000 });

    const tokenText = await page.getByTestId("kiosk-result-token").innerText();
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
