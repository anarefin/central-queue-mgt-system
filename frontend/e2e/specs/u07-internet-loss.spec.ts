import { test } from "../support/fixtures";

/**
 * SRS §27.2 U7: "Site loses internet connectivity (LAN stays up) for 20 minutes."
 *
 * Reaching this state needs the backend's own internet-reachability probe
 * (com.qms.platform.connectivity.InternetConnectivityMonitor, ticket 44) to actually observe a failure, which means
 * cutting the *server container's* egress for 20 real minutes while keeping the client-to-server LAN path up — not
 * something a Playwright script driving a browser can do to the stack it is talking to (it has no control over the
 * docker network from inside a browser context), and not safe to attempt against deploy/compose.yaml's default
 * network without a dedicated toggle this repo does not currently expose (e.g. a `docker network disconnect` step
 * outside this test's reach). Covered end to end against a fake reachability checker at
 * B/platform/connectivity/InternetLossDegradationIT (ticket 44), which is exactly what this UAT scenario's own
 * ticket already names as its acceptance test. Left here as fixme, matching the u02-u04 file's reasoning, rather
 * than silently omitted.
 */
test.fixme("U7: with internet down, tokens keep issuing and serving while remote join and Web Push show as unavailable", async () => {});
