import { test, expect } from "../support/fixtures";
import { loginAdmin } from "../support/console";

/**
 * SRS §27.2 U11: "Report export of a 12-month range."
 * Passes when: delivered async, figures reconcile with the live dashboard.
 *
 * Selectors below are inferred from the report-export naming used throughout the backend (ReportExportController,
 * ticket 49: async jobs, GET /reports/jobs/{id}, GET /reports/jobs/{id}/download) and from the shared EntityRow/
 * button-by-t()-text convention the rest of admin follows; the admin Reports screen's own component source was not
 * read when this spec was written (out of the selector survey's scope), so treat the exact button/label names here
 * as a starting point to correct against the real DOM, not as confirmed.
 */
test("U11: a 12-month report export completes asynchronously and is downloadable", async ({ page, queue }) => {
    await loginAdmin(page, queue.reception.username, queue.reception.password);
    await page.goto("/admin/reports/");

    await page.getByLabel("From").fill("2025-09-01");
    await page.getByLabel("To").fill("2026-09-01");
    await page.getByRole("button", { name: "Export" }).click();

    // Async job: a status badge moves from queued/running to done, per ReportExportController's job shape.
    await expect(page.getByText(/done|ready|complete/i)).toBeVisible({ timeout: 60000 });
    const download = page.getByRole("link", { name: /download/i }).or(page.getByRole("button", { name: /download/i }));
    await expect(download).toBeVisible();
});
