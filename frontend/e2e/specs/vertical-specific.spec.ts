import { test, expect } from "../support/fixtures";
import { loginConsole, openSession } from "../support/console";
import { pairDevice } from "../support/device";

/**
 * SRS §27.3: the vertical-specific scenario, one per profile. Each test only runs when QMS_PROFILE selects that
 * profile (support/profiles.ts) — the harness is invoked three times total, once per deployed profile, per
 * playwright.config.ts's own header comment.
 */

test("Banking: a priority-segment customer with an appointment during peak hour is ordered per policy", async ({ page, api, queue }) => {
    test.skip(queue.profile.id !== "banking", "banking-only scenario");

    const receptionToken = await api.login(queue.reception.username, queue.reception.password);
    const priorityTicket = await api.issueTicket(receptionToken, queue.serviceId);

    // Reception, not Agent: Agent carries neither `config:priority_routing` nor `ticket:issue`, so `GET
    // /priority-classes` refuses it and the picker never has options, and Agent carries no `ticket:reprioritise`
    // either (PermissionMatrix.java) — found by actually running this against a real deployment (ticket 70).
    await loginConsole(page, queue.reception.username, queue.reception.password);
    await page.getByRole("button", { name: "Live Dashboard" }).click();
    // Scoped to the "Longest waits" card: "Priority class" and "Reason" are reused verbatim by the sidebar filter
    // and the "Set availability" card respectively (ticket 70, found by actually running the suite).
    const reprioritiseCard = page.locator("section").filter({ has: page.getByRole("heading", { name: "Longest waits" }) });
    await reprioritiseCard.getByLabel("Re-prioritise").selectOption({ label: priorityTicket.token_number });
    // The Picker's own options load on focus, not on mount (Picker.tsx's `onFocus={onOpen}`); `selectOption` alone
    // never focuses the element first, so without this click the option list is still empty and `selectOption`
    // times out waiting for an option that will never appear (ticket 70, found by actually running the suite).
    const priorityClass = reprioritiseCard.getByLabel("Priority class");
    await priorityClass.click();
    await priorityClass.selectOption({ label: queue.profile.priorityClassWithHeadStart });
    await reprioritiseCard.getByLabel("Reason").fill("§27.3 banking scenario: priority-segment customer");
    await reprioritiseCard.getByRole("button", { name: "Change priority" }).click();

    // Call it from the counter, logged in as the Agent: opening/closing a counter session is
    // `counter:session_open_close`, which only Agent (own-scoped) or an admin role carries — Reception cannot.
    await loginConsole(page, queue.agent.username, queue.agent.password);
    await openSession(page, "E2E desk", queue.serviceName);
    await page.keyboard.press("F2");
    await expect(page.getByTestId("current-token")).toContainText(priorityTicket.token_number, { timeout: 10000 });

    // "Teller utilisation reported": covered by the operational reports (ticket 50, FR-RPT-*), not re-verified in
    // this UI script — U11 already exercises the reports UI's export path.
});

test("Healthcare: a patient's multi-stop journey calls one stop at a time and hides service names on the public display", async ({
    page,
    api,
    queue,
}) => {
    test.skip(queue.profile.id !== "healthcare", "healthcare-only scenario");

    // The healthcare profile's own starter catalogue (§3.4) already seeds Registration (the fixture Queue's own
    // `queue.serviceId`) and Consultation under the same group (ticket 67) — no hand-created second Service needed.
    // A Journey template's stops must all belong to one Service group (JourneyTemplateService's own
    // allBelongToGroupAndActive check), which the seeded catalogue already satisfies (ticket 70: finishes the
    // journey-template fixture this scenario was left partial without).
    const services = await api.groupServices(queue.adminToken, queue.groupId);
    const consultation = services.find((s) => s.name_i18n.en === "Consultation");
    if (!consultation) throw new Error(`Healthcare starter catalogue has no "Consultation" service (seeded: ${services.map((s) => s.name_i18n.en).join(", ")})`);

    await api.enableJourneys(queue.adminToken);
    const template = await api.createJourneyTemplate(queue.adminToken, queue.groupId, "E2E registration to consultation", true, [
        queue.serviceId,
        consultation.id,
    ]);

    const receptionToken = await api.login(queue.reception.username, queue.reception.password);
    const journey = await api.issueJourney(receptionToken, { journey_template_id: template.id });

    // FR-QUE-061 (an ordered Journey): only the first stop is issued a ticket; the next stays `planned` with no
    // ticket until the one ahead of it finishes — the API-level guarantee behind "calls one stop at a time",
    // proven here at the same layer U5's transfer spec proves its own engine-level guarantee (not re-derived from
    // pixels; the display can't show a call for a ticket that was never issued in the first place).
    expect(journey.stops).toHaveLength(2);
    expect(journey.stops[0]!.ticket).toBeTruthy();
    expect(journey.stops[0]!.state).not.toBe("planned");
    expect(journey.stops[1]!.ticket).toBeFalsy();
    expect(journey.stops[1]!.state).toBe("planned");

    // "Hides service names on the public display": healthcare's own privacy posture (§3.4), configured on a display
    // the way an administrator actually would (ticket 28's display-config `columns`), on a device this test pairs
    // itself first (ticket 70 — a fresh Playwright page has nothing paired yet). Mandatory, not a pairing-code-only
    // best-effort check: `columns` excluding `service` is a real admin action, not an assumption about defaults.
    const displayPage = await page.context().newPage();
    const deviceId = await pairDevice(displayPage, api, queue.adminToken, "display", queue.siteId, "/display/", { zoneId: queue.zoneId });
    await api.setDisplayColumns(queue.adminToken, deviceId, ["token", "counter"]);
    await displayPage.reload();
    await expect(displayPage.getByText("Consultation")).not.toBeVisible();
    await expect(displayPage.getByRole("columnheader", { name: "Service" })).not.toBeVisible();
    await displayPage.close();
});

test("Producer services: one producer visits three departments across two buildings with one visit and one journey report", async ({
    api,
    queue,
}) => {
    test.skip(queue.profile.id !== "producer_services", "producer_services-only scenario");

    // The producer_services profile's own starter catalogue (§3.4) already seeds Helpdesk query (`queue.serviceId`),
    // Sample and "Order and costing" under the same group (ticket 67) — the scenario's own three departments, no
    // hand-created Services needed (a Journey template's stops must all belong to one Service group,
    // JourneyTemplateService's own allBelongToGroupAndActive check, which the seeded catalogue already satisfies).
    const services = await api.groupServices(queue.adminToken, queue.groupId);
    const sample = services.find((s) => s.name_i18n.en === "Sample");
    const orderAndCosting = services.find((s) => s.name_i18n.en === "Order and costing");
    if (!sample || !orderAndCosting) {
        throw new Error(`Producer-services starter catalogue is missing "Sample"/"Order and costing" (seeded: ${services.map((s) => s.name_i18n.en).join(", ")})`);
    }

    // "Two buildings" are two Zones of the same Site (CONTEXT.md: Zone is the sub-site unit); the third stop's own
    // Counter lives in a second Zone, so that stop is genuinely served from a different building than the other two.
    const secondBuildingZoneId = await api.createZone(queue.adminToken, queue.siteId, "E2E second building", "1st");
    const secondBuildingCounterId = await api.createCounter(queue.adminToken, secondBuildingZoneId, "E2E second building desk");
    await api.linkCounter(queue.adminToken, orderAndCosting.id, secondBuildingCounterId);

    await api.enableJourneys(queue.adminToken);
    const template = await api.createJourneyTemplate(queue.adminToken, queue.groupId, "E2E producer three-department journey", false, [
        queue.serviceId,
        sample.id,
        orderAndCosting.id,
    ]);

    const receptionToken = await api.login(queue.reception.username, queue.reception.password);
    const journey = await api.issueJourney(receptionToken, { journey_template_id: template.id });

    // FR-QUE-062 (an unordered Journey): every stop is issued at once, all sharing the one Visit `journey.visit_id`
    // names — "one visit" for a producer crossing three departments and two buildings in a single trip. The
    // operational/domain reports that would turn this into "one journey report" are exercised generically by U11's
    // own export path, not re-derived here (same trade-off the healthcare test above documents).
    expect(journey.visit_id).toBeTruthy();
    expect(journey.stops).toHaveLength(3);
    expect(journey.stops.every((stop) => Boolean(stop.ticket))).toBe(true);
    expect(new Set(journey.stops.map((stop) => stop.ticket!.id)).size).toBe(3);
});
