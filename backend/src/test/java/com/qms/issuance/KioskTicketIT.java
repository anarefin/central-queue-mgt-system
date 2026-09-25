package com.qms.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Ticket 25's kiosk adapter over HTTP against real PostgreSQL: a paired kiosk device issues a walk-in ticket for
 * itself through {@code POST /kiosk/tickets}, scoped to its own site, with the same idempotency guarantee reception
 * gets (FR-ISS-001, FR-ISS-002, NFR-AVL-006, §8.2, §8.5, §20.1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class KioskTicketIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-kiosk-tickets");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    // ---- fixtures (mirrors IssuanceIT) --------------------------------------------------------------------------

    private record Setup(UUID site, UUID zone, UUID counter, UUID group, UUID service) {}

    private UUID newSite() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                id, "S-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID newZone(UUID site) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, building_label, floor_label) VALUES (?, ?, 'Ground waiting', 'Block A', 'Ground')", id, site);
        return id;
    }

    private UUID newCounter(UUID zone) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, '1')", id, zone);
        return id;
    }

    private UUID newGroup(UUID site, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\",\"bn\":\"বহির্বিভাগ\"}'::jsonb, ?)", id, site, prefix);
        return id;
    }

    private UUID newService(UUID group, String prefix, String channelsJson, boolean active) {
        return newService(group, prefix, channelsJson, active, "not_required");
    }

    private UUID newService(UUID group, String prefix, String channelsJson, boolean active, String visitorIdentifier) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode, active, requires_visitor_id)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\",\"bn\":\"পরামর্শ\"}'::jsonb, ?, 10, 30, ?::jsonb, 'both', ?, ?)",
                id, group, prefix, channelsJson, active, visitorIdentifier);
        return id;
    }

    private void link(UUID counter, UUID service) {
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", counter, service);
    }

    /** {@code setup()} does not create a team (no test needed one until ticket 26); these tests do. */
    private void newTeam(UUID group) {
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, 'Outpatient team')", UUID.randomUUID(), group);
    }

    private Setup setup(String prefix) {
        UUID site = newSite();
        UUID zone = newZone(site);
        UUID counter = newCounter(zone);
        UUID group = newGroup(site, "G" + prefix);
        UUID service = newService(group, prefix, "[\"reception\",\"kiosk\"]", true);
        link(counter, service);
        return new Setup(site, zone, counter, group, service);
    }

    private String staffToken(Role role, UUID... sites) throws Exception {
        UUID id = UUID.randomUUID();
        String username = role.wire() + "-" + id;
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                id, username, new BCryptPasswordEncoder(12).encode(PASSWORD), role.wire(), "en");
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, id);
            ps.setString(3, role.wire());
            ps.setArray(4, connection.createArrayOf("uuid", sites));
            ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
            return ps;
        });
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(login.getResponse().getStatus()).as(login.getResponse().getContentAsString()).isEqualTo(200);
        return JsonPath.read(login.getResponse().getContentAsString(), "$.access_token");
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private String pairingCode(String admin, UUID site) throws Exception {
        MvcResult created = call(post("/api/v1/devices/pairing-codes"), admin,
                "{\"kind\":\"kiosk\",\"site_id\":\"" + site + "\",\"label\":\"Lobby kiosk\"}");
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        return field(created, "$.code");
    }

    /** Pairs a fresh kiosk device for {@code site} and returns its access token. */
    private String kioskToken(UUID site) throws Exception {
        String admin = staffToken(Role.ORG_ADMIN);
        MvcResult paired = call(post("/api/v1/devices/pair"), null, "{\"code\":\"" + pairingCode(admin, site) + "\"}");
        assertThat(status(paired)).as(body(paired)).isEqualTo(201);
        return field(paired, "$.access_token");
    }

    private MvcResult issue(String token, String key, UUID service) throws Exception {
        return issue(token, key, service, null, null, null);
    }

    private MvcResult issue(String token, String key, UUID service, UUID visitorId, UUID agentId, String customLevelId) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/kiosk/tickets");
        if (key != null) request.header("Idempotency-Key", key);
        StringBuilder body = new StringBuilder("{\"service_id\":\"" + service + "\"");
        if (visitorId != null) body.append(",\"visitor_id\":\"").append(visitorId).append('"');
        if (agentId != null) body.append(",\"agent_id\":\"").append(agentId).append('"');
        if (customLevelId != null) body.append(",\"custom_level_id\":\"").append(customLevelId).append('"');
        body.append('}');
        return call(request, token, body.toString());
    }

    // ---- ticket 26 fixtures: visitors, the kiosk selection tree, teams and on-duty agents ------------------------

    private UUID newVisitor(String externalCode, String phone, String name, String category) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO visitor (id, external_code, phone, name, category, created_at) VALUES (?, ?, ?, ?, ?, now())", id, externalCode, phone, name, category);
        return id;
    }

    private void configureSelectionTree(UUID group, boolean teamSelectable, boolean individualSelectable, String customLevelOptionsJson) {
        jdbc.update(
                "UPDATE service_group SET team_selectable = ?, individual_selectable = ?, custom_level_name_i18n = ?::jsonb, custom_level_options = ?::jsonb WHERE id = ?",
                teamSelectable, individualSelectable, customLevelOptionsJson == null ? null : "{\"en\":\"Preferred language\"}", customLevelOptionsJson, group);
    }

    /**
     * A member of the group's one team, on duty right now (a live {@code counter_session}) at a counter of its own,
     * serving the group's services — at most one live session may occupy a counter, so each on-duty agent in a test
     * needs a fresh one.
     */
    private UUID onDutyAgent(UUID group, UUID zone, String displayName) {
        return onDutyAgent(group, group, zone, displayName);
    }

    /**
     * A member of {@code teamGroup}'s team whose live session serves {@code servedGroup}'s services: with two different
     * groups, an Agent on duty for another department who cannot draw this group's tickets (FR-QUE-003).
     */
    private UUID onDutyAgent(UUID teamGroup, UUID servedGroup, UUID zone, String displayName) {
        UUID group = teamGroup;
        UUID user = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language, active) VALUES (?, ?, ?, ?, 'en', true)",
                user, "agent-" + user, new BCryptPasswordEncoder(12).encode(PASSWORD), displayName);
        jdbc.update("INSERT INTO team_member (team_id, user_id) SELECT id, ? FROM team WHERE service_group_id = ?", user, group);
        jdbc.update(
                "INSERT INTO counter_session (id, counter_id, agent_id, opened_at, services, state)"
                        + " VALUES (?, ?, ?, now(), ARRAY(SELECT id FROM service WHERE service_group_id = ?), 'open')",
                UUID.randomUUID(), newCounter(zone), user, servedGroup);
        return user;
    }

    /** A member of the group's team who is not on duty (no live session): FR-ISS-012 must never offer them. */
    private UUID offDutyAgent(UUID group, String displayName) {
        UUID user = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language, active) VALUES (?, ?, ?, ?, 'en', true)",
                user, "agent-" + user, new BCryptPasswordEncoder(12).encode(PASSWORD), displayName);
        jdbc.update("INSERT INTO team_member (team_id, user_id) SELECT id, ? FROM team WHERE service_group_id = ?", user, group);
        return user;
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static <T> T field(MvcResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    private static String errorCode(MvcResult result) throws Exception {
        return field(result, "$.error.code");
    }

    private int count(String table, String where, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, args);
    }

    // ---- issuing (§8.2, FR-ISS-001, FR-ISS-002) -----------------------------------------------------------------

    @Test
    void aPairedKioskIssuesAWalkInTicketForItsOwnSiteWithASecretAndAQueuePosition() throws Exception {
        Setup s = setup("K");
        String kiosk = kioskToken(s.site());

        MvcResult issued = issue(kiosk, UUID.randomUUID().toString(), s.service());

        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        assertThat((String) field(issued, "$.token_number")).isEqualTo("K-001");
        assertThat((String) field(issued, "$.state")).isEqualTo("waiting");
        assertThat((String) field(issued, "$.origin_channel")).isEqualTo("kiosk");
        assertThat((Integer) field(issued, "$.position")).isEqualTo(1);
        assertThat((String) field(issued, "$.secret")).hasSizeGreaterThanOrEqualTo(32);
        UUID id = UUID.fromString(field(issued, "$.id"));
        assertThat(jdbc.queryForObject("SELECT actor_type FROM ticket_event WHERE ticket_id = ?", String.class, id)).isEqualTo("device");
        assertThat(count("ticket", "id = ?", id)).isEqualTo(1);
        Map<String, Object> audit = jdbc.queryForMap("SELECT actor_role, after::text AS after FROM audit_log WHERE action = 'ticket.issued' AND entity_id = ?", id);
        assertThat(audit.get("actor_role")).isEqualTo("kiosk");
        assertThat((String) audit.get("after")).contains("K-001").contains("kiosk");
    }

    @Test
    void replayingAnIdempotencyKeyReturnsTheOriginalTicketAndIssuesNothingMore() throws Exception {
        Setup s = setup("R");
        String kiosk = kioskToken(s.site());
        String key = UUID.randomUUID().toString();

        MvcResult first = issue(kiosk, key, s.service());
        MvcResult replay = issue(kiosk, key, s.service());

        assertThat(status(first)).isEqualTo(201);
        assertThat(status(replay)).isEqualTo(201);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(replay)).isEqualTo(body(first));
        assertThat(count("ticket", "service_id = ?", s.service())).isEqualTo(1);
    }

    @Test
    void anIdempotencyKeyIsRequired() throws Exception {
        Setup s = setup("M");
        String kiosk = kioskToken(s.site());

        MvcResult missing = issue(kiosk, null, s.service());

        assertThat(status(missing)).isEqualTo(400);
        assertThat(errorCode(missing)).isEqualTo("validation_failed");
        assertThat(count("ticket", "service_id = ?", s.service())).isZero();
    }

    // ---- permission and scope (FR-CFG-108, kiosk is site-scoped like reception) ---------------------------------

    @Test
    void onlyAKioskDeviceMayCallTheEndpointAndNeverForAnotherSitesService() throws Exception {
        Setup mine = setup("SA");
        Setup theirs = setup("SB");
        String kiosk = kioskToken(mine.site());
        String reception = staffToken(Role.RECEPTION_OPERATOR, mine.site());

        assertThat(status(issue(kiosk, UUID.randomUUID().toString(), theirs.service())))
                .as("a kiosk cannot issue for a service outside its own site").isEqualTo(403);
        assertThat(status(issue(reception, UUID.randomUUID().toString(), mine.service())))
                .as("staff tokens cannot call the kiosk-only endpoint").isEqualTo(403);
        assertThat(status(issue(null, UUID.randomUUID().toString(), mine.service()))).isEqualTo(401);

        MvcResult ok = issue(kiosk, UUID.randomUUID().toString(), mine.service());
        assertThat(status(ok)).as(body(ok)).isEqualTo(201);
    }

    @Test
    void aDisplayDeviceCannotIssueTickets() throws Exception {
        Setup s = setup("D");
        String admin = staffToken(Role.ORG_ADMIN);
        UUID zone = s.zone();
        MvcResult code = call(post("/api/v1/devices/pairing-codes"), admin,
                "{\"kind\":\"display\",\"site_id\":\"" + s.site() + "\",\"zone_id\":\"" + zone + "\",\"label\":\"Lobby screen\"}");
        assertThat(status(code)).as(body(code)).isEqualTo(201);
        MvcResult paired = call(post("/api/v1/devices/pair"), null, "{\"code\":\"" + field(code, "$.code") + "\"}");
        String display = field(paired, "$.access_token");

        assertThat(status(issue(display, UUID.randomUUID().toString(), s.service()))).isEqualTo(403);
    }

    // ---- issuance rules still apply through this channel (ticket 21) ---------------------------------------------

    @Test
    void anInactiveOrNonKioskServiceRefusesWithAReason() throws Exception {
        Setup s = setup("N");
        UUID receptionOnly = newService(s.group(), "NR", "[\"reception\"]", true);
        UUID inactive = newService(s.group(), "NI", "[\"kiosk\"]", false);
        String kiosk = kioskToken(s.site());

        MvcResult wrongChannel = issue(kiosk, UUID.randomUUID().toString(), receptionOnly);
        assertThat(status(wrongChannel)).isEqualTo(409);
        assertThat(errorCode(wrongChannel)).isEqualTo("conflict");
        assertThat((String) field(wrongChannel, "$.error.details.reason")).isEqualTo("channel_not_allowed");

        MvcResult inactiveService = issue(kiosk, UUID.randomUUID().toString(), inactive);
        assertThat(errorCode(inactiveService)).isEqualTo("conflict");
        assertThat((String) field(inactiveService, "$.error.details.reason")).isEqualTo("service_inactive");

        assertThat(status(issue(kiosk, UUID.randomUUID().toString(), UUID.randomUUID()))).isEqualTo(404);
    }

    // ---- identification and the selection tree (ticket 26, FR-ISS-010..014, FR-CFG-013) --------------------------

    @Test
    void aServiceRequiringAMandatoryVisitorIdentifierRefusesWithoutOneAndIssuesWithOne() throws Exception {
        Setup s = setup("V");
        UUID mandatory = newService(s.group(), "VM", "[\"reception\",\"kiosk\"]", true, "mandatory");
        UUID visitor = newVisitor("V-CODE-1", "+8801000000001", "Karim Rahman", "citizen");
        String kiosk = kioskToken(s.site());

        MvcResult refused = issue(kiosk, UUID.randomUUID().toString(), mandatory);
        assertThat(status(refused)).isEqualTo(400);
        assertThat(errorCode(refused)).isEqualTo("validation_failed");
        assertThat((String) field(refused, "$.error.details.fields[0].field")).isEqualTo("visitor_id");

        MvcResult issued = issue(kiosk, UUID.randomUUID().toString(), mandatory, visitor, null, null);
        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        UUID ticketId = UUID.fromString(field(issued, "$.id"));
        assertThat(jdbc.queryForObject("SELECT visitor_id FROM ticket WHERE id = ?", UUID.class, ticketId)).isEqualTo(visitor);
    }

    @Test
    void identifyResolvesACodeOrPhoneToOnlyNameAndCategoryAndNothingElse() throws Exception {
        Setup s = setup("I");
        newVisitor("I-CODE-1", "+8801700000001", "Farhana Akter", "vip");
        String kiosk = kioskToken(s.site());
        String reception = staffToken(Role.RECEPTION_OPERATOR, s.site());

        MvcResult byCode = call(get("/api/v1/kiosk/visitors/identify?q=I-CODE-1"), kiosk, null);
        assertThat(status(byCode)).as(body(byCode)).isEqualTo(200);
        assertThat((String) field(byCode, "$.name")).isEqualTo("Farhana Akter");
        assertThat((String) field(byCode, "$.category")).isEqualTo("vip");
        assertThat(body(byCode)).as("no phone, external code or flags reach the kiosk (FR-ISS-014)").doesNotContain("+8801700000001").doesNotContain("I-CODE-1");

        MvcResult byPhone = call(get("/api/v1/kiosk/visitors/identify").param("q", "+8801700000001"), kiosk, null);
        assertThat((String) field(byPhone, "$.name")).isEqualTo("Farhana Akter");

        assertThat(status(call(get("/api/v1/kiosk/visitors/identify?q=NOSUCHCODE"), kiosk, null))).isEqualTo(404);
        assertThat(status(call(get("/api/v1/kiosk/visitors/identify?q=I-CODE-1"), reception, null))).as("staff tokens cannot call the kiosk-only endpoint").isEqualTo(403);
        assertThat(status(call(get("/api/v1/kiosk/visitors/identify?q=I-CODE-1"), null, null))).isEqualTo(401);
    }

    @Test
    void anIndividualAgentMustBeOnDutyOnTheGroupsTeamAndTheLevelMustBeEnabled() throws Exception {
        Setup s = setup("A");
        newTeam(s.group());
        UUID onDuty = onDutyAgent(s.group(), s.zone(), "Nusrat Jahan");
        UUID offDuty = offDutyAgent(s.group(), "Off Duty");
        UUID otherGroup = newGroup(s.site(), "AX");
        newTeam(otherGroup);
        UUID outsider = onDutyAgent(otherGroup, s.zone(), "Elsewhere");
        newService(otherGroup, "AY", "[\"kiosk\"]", true);
        UUID servingOtherGroup = onDutyAgent(s.group(), otherGroup, s.zone(), "On the team, serving elsewhere");
        String kiosk = kioskToken(s.site());

        MvcResult levelDisabled = issue(kiosk, UUID.randomUUID().toString(), s.service(), null, onDuty, null);
        assertThat(status(levelDisabled)).isEqualTo(409);
        assertThat((String) field(levelDisabled, "$.error.details.reason")).isEqualTo("individual_not_selectable");

        configureSelectionTree(s.group(), true, true, null);

        assertThat((String) field(issue(kiosk, UUID.randomUUID().toString(), s.service(), null, offDuty, null), "$.error.details.reason"))
                .as("a team member who is not on duty").isEqualTo("agent_not_on_duty");
        assertThat((String) field(issue(kiosk, UUID.randomUUID().toString(), s.service(), null, outsider, null), "$.error.details.reason"))
                .as("on duty, but on another group's team").isEqualTo("agent_not_on_duty");
        assertThat((String) field(issue(kiosk, UUID.randomUUID().toString(), s.service(), null, servingOtherGroup, null), "$.error.details.reason"))
                .as("on the team, but its session serves none of this group's services, so it could never call the ticket")
                .isEqualTo("agent_not_on_duty");
        assertThat(status(issue(kiosk, UUID.randomUUID().toString(), s.service(), null, UUID.randomUUID(), null))).isEqualTo(409);

        MvcResult issued = issue(kiosk, UUID.randomUUID().toString(), s.service(), null, onDuty, null);
        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        UUID ticketId = UUID.fromString(field(issued, "$.id"));
        assertThat(jdbc.queryForObject("SELECT target_agent_id FROM ticket WHERE id = ?", UUID.class, ticketId)).isEqualTo(onDuty);
    }

    @Test
    void theOnDutyAgentsEndpointListsOnlyOnDutyTeamMembersAndWarnsWhenTheirQueueIsLongerThanTheGroups() throws Exception {
        Setup s = setup("Q");
        newTeam(s.group());
        UUID busy = onDutyAgent(s.group(), s.zone(), "Busy Agent");
        UUID idle = onDutyAgent(s.group(), s.zone(), "Idle Agent");
        offDutyAgent(s.group(), "Not Here");
        UUID otherGroup = newGroup(s.site(), "QX");
        newService(otherGroup, "QY", "[\"kiosk\"]", true);
        onDutyAgent(s.group(), otherGroup, s.zone(), "Serving Elsewhere");
        configureSelectionTree(s.group(), true, true, null);
        String kiosk = kioskToken(s.site());

        // Two tickets already personally targeted at "busy"; none at "idle"; the group's own queue has one (this one).
        issue(kiosk, UUID.randomUUID().toString(), s.service(), null, busy, null);
        issue(kiosk, UUID.randomUUID().toString(), s.service(), null, busy, null);

        MvcResult agents = call(get("/api/v1/kiosk/groups/" + s.group() + "/agents"), kiosk, null);
        assertThat(status(agents)).as(body(agents)).isEqualTo(200);
        List<Map<String, Object>> items = field(agents, "$.items");
        assertThat(items).extracting(i -> i.get("name")).containsExactlyInAnyOrder("Busy Agent", "Idle Agent");
        Map<String, Object> busyItem = items.stream().filter(i -> busy.toString().equals(i.get("agent_id"))).findFirst().orElseThrow();
        Map<String, Object> idleItem = items.stream().filter(i -> idle.toString().equals(i.get("agent_id"))).findFirst().orElseThrow();
        assertThat((Boolean) busyItem.get("queue_longer_than_group")).isTrue();
        assertThat((Boolean) idleItem.get("queue_longer_than_group")).isFalse();

        assertThat(status(call(get("/api/v1/kiosk/groups/" + UUID.randomUUID() + "/agents"), kiosk, null))).isEqualTo(404);
        Setup theirs = setup("QB");
        assertThat(status(call(get("/api/v1/kiosk/groups/" + theirs.group() + "/agents"), kiosk, null))).as("another site's group").isEqualTo(403);
    }

    @Test
    void aCustomLevelPickMustBeOneOfTheGroupsCurrentOptionsAndIsRecordedOnTheTicket() throws Exception {
        Setup s = setup("C");
        configureSelectionTree(
                s.group(), false, false,
                "[{\"id\":\"priority\",\"name_i18n\":{\"en\":\"Priority\"}},{\"id\":\"standard\",\"name_i18n\":{\"en\":\"Standard\"}}]");
        String kiosk = kioskToken(s.site());

        MvcResult invalid = issue(kiosk, UUID.randomUUID().toString(), s.service(), null, null, "does-not-exist");
        assertThat(status(invalid)).isEqualTo(400);
        assertThat((String) field(invalid, "$.error.details.fields[0].field")).isEqualTo("custom_level_id");

        MvcResult issued = issue(kiosk, UUID.randomUUID().toString(), s.service(), null, null, "priority");
        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        UUID ticketId = UUID.fromString(field(issued, "$.id"));
        assertThat(jdbc.queryForObject("SELECT custom_level_id FROM ticket WHERE id = ?", String.class, ticketId)).isEqualTo("priority");
    }

    // ---- NFR-PERF-001: issuance completes in under 2 s at P95 ------------------------------------------------------

    @Test
    void issuingThroughTheKioskCompletesWithinTwoSecondsAtP95() throws Exception {
        Setup s = setup("P");
        // Several kiosks share the load so no single device's 30-per-minute rate limit (ticket 21, FR-ISS-003) is hit.
        List<String> kiosks = new ArrayList<>();
        for (int i = 0; i < 5; i++) kiosks.add(kioskToken(s.site()));
        List<Long> millis = new ArrayList<>();

        for (int i = 0; i < 40; i++) {
            String kiosk = kiosks.get(i % kiosks.size());
            long t0 = System.nanoTime();
            MvcResult issued = issue(kiosk, UUID.randomUUID().toString(), s.service());
            millis.add((System.nanoTime() - t0) / 1_000_000);
            assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        }

        Collections.sort(millis);
        long p95 = millis.get((int) Math.ceil(millis.size() * 0.95) - 1);
        assertThat(p95).as("P95 of %s kiosk issuances, in ms: %s", millis.size(), millis).isLessThan(2000);
    }
}
