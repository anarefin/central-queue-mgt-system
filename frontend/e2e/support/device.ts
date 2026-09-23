import { Page, expect } from "@playwright/test";
import { Api } from "./api";

/**
 * Pairs a fresh kiosk or display (ticket 24) with a real pairing code from the fixture admin, the way an
 * administrator actually pairs a device (FR-OPS-011) — a Playwright page has its own isolated storage per test, so
 * every spec that drives a kiosk or display must pair it itself rather than assuming one is already paired
 * (ticket 70: `support/kiosk.ts` previously never did this at all).
 */
export async function pairDevice(
    page: Page,
    api: Api,
    adminToken: string,
    kind: "kiosk" | "display",
    siteId: string,
    basePath: string,
    opts: { zoneId?: string; label?: string } = {}
): Promise<string> {
    const label = opts.label ?? `E2E ${kind} ${Date.now()}`;
    const code = await api.createPairingCode(adminToken, kind, siteId, opts.zoneId, label);

    await page.goto(basePath);
    await page.getByLabel("Pairing code").fill(code);
    await page.getByRole("button", { name: "Pair" }).click();
    await expect(page.getByLabel("Pairing code")).not.toBeVisible({ timeout: 15000 });

    const device = await api.findDeviceByLabel(adminToken, label);
    if (!device) throw new Error(`Paired ${kind} device with label "${label}" was not found via GET /devices afterwards`);
    return device.id;
}
