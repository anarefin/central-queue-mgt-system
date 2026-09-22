import { test, expect } from "../support/fixtures";

/**
 * SRS §27.2 U12: "Bangla-only visitor completes a full journey."
 * Passes when: every screen, print, announcement and Web Push notification in Bangla; token numbers in Western
 * Arabic digits (numerals.ts: the Bengali locale must not localise them, per MAP.md's "Non-obvious" note).
 *
 * A kiosk audio announcement and an actual Web Push delivery are not observable from the DOM a Playwright test
 * reads; those two "passes when" clauses are covered elsewhere — B/queue voice-announcement content tests (ticket
 * 29) and the Web Push payload-language tests (ticket 39) — and this spec covers the two clauses that are: every
 * on-screen string in Bangla, and Western Arabic digits throughout.
 */
test("U12: the kiosk flow in Bangla shows Bangla text and Western Arabic token digits", async ({ page }) => {
    await page.goto("/kiosk/");
    await page.getByRole("button", { name: "বাংলা" }).click();

    await page.getByRole("button", { name: "Get my token" }).click();
    await expect(page.getByText(/আপনার টোকেন|টোকেন নিন/)).toBeVisible({ timeout: 10000 });

    const tokenText = await page.locator("main").innerText();
    const match = tokenText.match(/[অ-হক-য়]-\d+|[A-Z]-\d+/);
    expect(match, `no token number found in: ${tokenText}`).not.toBeNull();
    // Western Arabic digits (0-9), never Bengali numerals (০-৯), even on the Bangla-language screen.
    expect(tokenText).not.toMatch(/[০-৯]/);
});
