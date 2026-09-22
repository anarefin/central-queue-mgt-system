import { test } from "../support/fixtures";

/**
 * SRS §27.2 U10: "Daily reset boundary passes."
 * Passes when: sequences restart, prior day's data intact in reports.
 *
 * The reset runs once cluster-wide under a database lock at the Site's own local reset time (ticket 08,
 * ADR-0010) — crossing that boundary for real means waiting for a real day to turn over (or a real clock change),
 * which a browser-driven Playwright script cannot fast-forward on a real deployment any more than U2-U4 or U7 can
 * (see the neighbouring spec files). Covered end to end against a controlled clock at
 * B/configuration/... (`IT#theSequenceRestartsAtTheSiteLocalResetTimeAndPriorDaysStayIntact`, cited in
 * docs/traceability-matrix.md's ticket 08 U10 row), which already demonstrates numbering restarts at the
 * site-local reset time with no duplicate number and the prior day's data intact in reports. Left here as fixme
 * for the same reason as the other time-dependent scenarios.
 */
test.fixme("U10: after the daily reset boundary, numbering restarts and the prior day's data stays intact in reports", async () => {});
