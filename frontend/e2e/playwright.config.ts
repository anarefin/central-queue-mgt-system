import { defineConfig, devices } from "@playwright/test";

/**
 * SRS §27.2/§27.3, ticket 61: Playwright UAT scripts against a real running stack (deploy/compose.yaml), one
 * origin, five basePaths (ADR-0012 — no dev server, these are the same static exports production serves).
 *
 * Run against whichever profile the target deployment already has applied (§3.4: banking | healthcare |
 * producer_services), one deployment per profile, config changed between runs — never the code (CFG-001):
 *
 *   QMS_PROFILE=banking BASE_URL=http://localhost:8080 pnpm e2e
 *   QMS_PROFILE=healthcare BASE_URL=http://localhost:8080 pnpm e2e
 *   QMS_PROFILE=producer_services BASE_URL=http://localhost:8080 pnpm e2e
 *
 * See ../../.scratch/qms-phase1/issues/61-acceptance-suite.md and docs/traceability-matrix.md (ticket 61 section)
 * for which of U1-U12 and which vertical scenarios this harness could actually be executed against in the sandbox
 * this suite was written in (no network access to install @playwright/test or its browsers — see that note before
 * assuming a green run here means anything beyond "the source compiles").
 */
export default defineConfig({
    testDir: "./specs",
    fullyParallel: false, // most scenarios share one seeded site/queue; parallel runs would race each other's state
    retries: 0,
    reporter: [["list"], ["html", { open: "never", outputFolder: "report" }]],
    use: {
        baseURL: process.env.BASE_URL || "http://localhost:8080",
        trace: "retain-on-failure",
        screenshot: "only-on-failure",
    },
    projects: [
        {
            name: "chromium",
            use: { ...devices["Desktop Chrome"] },
        },
    ],
});
