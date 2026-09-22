import { test } from "../support/fixtures";

/**
 * SRS §27.2 U2, U3, U4: appointment check-in ordering, remote-join "approaching" alert, and remote forfeit at the
 * arrival deadline.
 *
 * Each of these three scenarios hinges on a server-side, time-driven trigger a UI-only Playwright script cannot
 * fast-forward against a real deployment without either waiting for real wall-clock minutes (making the suite slow
 * and flaky) or a test-only time-travel endpoint this codebase does not expose in the serving profile (its
 * MutableClock is a test-JVM fixture, `com.qms.support.MutableClock`, not a production API):
 *
 *   - U2 needs a real appointment slot time to arrive relative to a walk-in's issuance time.
 *   - U3 needs the "approaching" alert's own configured lead time to elapse (FR-MOB-013).
 *   - U4 needs the arrival deadline itself to elapse (FR-MOB-022).
 *
 * All three are already covered end to end against a real clock the test controls at the integration level:
 * B/appointment/AppointmentCheckInIT (U2), B/queue/RemoteArrivalIT and the RemoteArrivalScheduler tests (U3's
 * approaching alert), B/queue/RemoteArrivalTest's forfeit transitions plus RemoteArrivalIT's scheduled sweep (U4).
 * Left here as fixme rather than silently omitted, so the gap is visible in a test run rather than only in a
 * comment — see docs/traceability-matrix.md's ticket 61 section for the same note against U2/U3/U4.
 */
test.fixme("U2: a checked-in appointment is ordered ahead of later walk-ins but not ahead of a ticket in service", async () => {});
test.fixme("U3: a remote visitor's position is preserved through the approaching alert and they are called normally", async () => {});
test.fixme("U4: a remote visitor who misses the arrival deadline is forfeited per the configured policy", async () => {});
