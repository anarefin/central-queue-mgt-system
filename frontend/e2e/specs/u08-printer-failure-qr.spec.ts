import { test, expect } from "../support/fixtures";
import { forcePrintFailure, issueViaKiosk } from "../support/kiosk";

/**
 * SRS §27.2 U8: "Printer runs out of paper mid-day."
 * Passes when: ticket still created; number and QR shown; QR opens the ticket page in the mobile web app.
 */
test("U8: a print failure still issues the ticket and its QR opens the visitor ticket page", async ({ page, api, queue }) => {
    await forcePrintFailure(page);
    const token = await issueViaKiosk(page, api, queue.adminToken, queue.siteId, queue.serviceName);
    expect(token).toMatch(/^[A-Z]-\d+$/);

    await expect(page.getByText("your token is saved")).toBeVisible();
    const qr = page.getByRole("img", { name: new RegExp(token) });
    await expect(qr).toBeVisible();
    expect(await qr.evaluate((el) => el.tagName.toLowerCase())).toBe("svg");

    // The QR encodes ticketStatusUrl(ticket): "<origin>/visitor/?t=<id>#s=<secret>" (KioskFlow.tsx). This spec
    // reads that URL back off the DOM's own `data-qr-value` attribute (@qms/ui's QrCode component, ticket 65)
    // rather than decoding pixels from the rendered <svg> — what an actual camera scan does, but out of scope for
    // a DOM-level Playwright check; a full optical-QR regression belongs to a device test (already flagged as a gap
    // alongside NFR-PERF-007 in ticket 26's own traceability-matrix row), not this UAT script. Mandatory, not
    // best-effort (ticket 70): `data-qr-value` is guaranteed present by ticket 65.
    const href = await qr.getAttribute("data-qr-value");
    expect(href, "QrCode did not expose data-qr-value").toBeTruthy();
    await page.goto(href!.replace(/^https?:\/\/[^/]+/, ""));
    await expect(page.getByText(new RegExp(token))).toBeVisible();
});
