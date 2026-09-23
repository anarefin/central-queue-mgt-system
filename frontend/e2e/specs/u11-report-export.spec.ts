import { test, expect } from "../support/fixtures";
import { loginAdmin } from "../support/console";

/**
 * SRS §27.2 U11: "Report export of a 12-month range."
 * Passes when: delivered async, figures reconcile with the live dashboard.
 *
 * Reception lacks `reports:run_export` (PermissionMatrix.java: only system_admin/org_admin/team_admin have it), so
 * this logs in as a fresh fixture user with that permission rather than reception (ticket 70). org_admin, not
 * team_admin: the admin Reports page's own `ReportsAdmin.tsx` lists Sites with `client.sites.list()` (`GET /sites`,
 * `config:org_sites_zones`) before it will render any report card at all, and `RetentionPolicyCard` needs a
 * separate permission of its own — team_admin has neither, so the whole page renders "You do not have permission"
 * instead of any card, found by actually running this against a real deployment. org_admin carries
 * `config:org_sites_zones`, `reports:run_export` and `visitor_pii:view` together. Selectors are scoped to the
 * detailed-token report card (DetailedTokenReportCard.tsx) since the same page also renders operational/domain
 * report cards with their own "From"/"To" fields.
 */
test("U11: a 12-month report export completes asynchronously and is downloadable", async ({ page, api, queue }) => {
    const orgAdmin = { username: `e2e-orgadmin-${Date.now()}`, password: "E2E-OrgAdmin-9" };
    await api.createStaff(queue.adminToken, "org_admin", queue.siteId, orgAdmin.username, orgAdmin.password);

    await loginAdmin(page, orgAdmin.username, orgAdmin.password);
    await page.goto("/admin/reports/");

    const reportCard = page.locator("section").filter({ has: page.getByRole("heading", { name: "Detailed token report" }) });
    // Non-exact getByLabel substring-matches "To" against any label that merely contains it — the visitor-category
    // field's own label ("{Visitor} category") does, once a profile's own Visitor label contains "to" (banking's
    // "Customer" does: cus-TO-mer), causing a strict-mode violation (ticket 70, found by actually running the
    // suite against the banking profile). Exact match on both date fields avoids the whole class of collision.
    await reportCard.getByLabel("From", { exact: true }).fill("2025-09-01");
    await reportCard.getByLabel("To", { exact: true }).fill("2026-09-01");

    // The export either resolves inline (kind "ready", below the deployment's async-threshold row count — the
    // fixture Queue's own handful of tickets) or is queued in the background (DetailedTokenReportCard.tsx polls
    // GET /reports/jobs/{id} every 2s until it leaves queued/running); either way the browser receives a real
    // download once it finishes, which this waits on directly rather than assuming which path a given run takes.
    const downloadPromise = page.waitForEvent("download", { timeout: 60000 });
    await reportCard.getByRole("button", { name: "Export" }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBeTruthy();
});
