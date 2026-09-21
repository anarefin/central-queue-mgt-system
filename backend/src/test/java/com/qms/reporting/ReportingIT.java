package com.qms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.issuance.ActorType;
import com.qms.issuance.Channels;
import com.qms.issuance.IssuanceService;
import com.qms.issuance.IssueCommand;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Ticket 48 against real PostgreSQL (SRS §16, §18.5, ADR-0006): the reporting store is refreshed from {@code ticket}
 * and {@code ticket_event} (never read directly by a report, FR-RPT-020), "tickets issued" counts chain heads, and
 * {@code POST /reports/detailed-token/run} answers the detailed token report (§16.1) filtered (FR-RPT-001), paged
 * and sorted (FR-RPT-002) on {@code reporting.ticket_fact} alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, ReportingIT.Clocks.class})
class ReportingIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    static final Instant BASE = Instant.parse("2026-09-19T10:00:00Z");

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock testClock() {
            return MutableClock.now();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
        registry.add("qms.queue.call-timeout-check-cron", () -> "-");
        registry.add("qms.queue.remote-arrival-check-cron", () -> "-");
        registry.add("qms.reporting.refresh-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-reporting");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssuanceService issuance;
    @Autowired MutableClock clock;
    @Autowired ReportingRefreshScheduler scheduler;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
        // The refresh watermark is one row shared by the whole database (FR-RPT-020's own state, not a test fixture):
        // since `ticket_event.recorded_at` is written off this test's own clock, and each test resets that clock back
        // to BASE, a watermark left behind by an earlier test (advanced past BASE) would silently swallow every event
        // this test writes. Starting each test from a clean watermark keeps them independent.
        jdbc.update("UPDATE reporting.refresh_watermark SET last_recorded_at = NULL");
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private record World(UUID site, UUID zone, UUID group, UUID service) {}

    private record Staff(UUID id, String token) {}

    private World world() {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "R-" + site.toString().substring(0, 8));
        UUID zone = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, floor_label) VALUES (?, ?, 'Hall', '1st')", zone, site);
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'G')", group, site);
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, 'Outpatient team')", UUID.randomUUID(), group);
        return new World(site, zone, group, newService(group, "A", "Consultation"));
    }

    private UUID newService(UUID group, String prefix, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode)"
                        + " VALUES (?, ?, ?::jsonb, ?, 10, 30, '[\"reception\",\"kiosk\"]'::jsonb, 'both')",
                id, group, "{\"en\":\"" + name + "\"}", prefix);
        return id;
    }

    private UUID newOutcome(UUID service, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO outcome_code (id, service_id, code, label_i18n) VALUES (?, ?, ?, ?::jsonb)", id, service, code, "{\"en\":\"" + code + "\"}");
        return id;
    }

    private UUID counter(UUID zone, UUID service, String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, ?)", id, zone, label);
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", id, service);
        return id;
    }

    private UUID newClass(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO priority_class (id, name_i18n, headstart_minutes, created_at, updated_at) VALUES (?, ?::jsonb, 0, now(), now())",
                id, "{\"en\":\"" + name + "\"}");
        return id;
    }

    private UUID visitor(String code, String name, String category) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO visitor (id, external_code, name, category, created_at) VALUES (?, ?, ?, ?, now())", id, code, name, category);
        return id;
    }

    private Staff staff(Role role, UUID site, UUID teamOf) throws Exception {
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            UUID user = UUID.randomUUID();
            String username = role.wire() + "-" + user;
            jdbc.update(
                    "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                    user, username, new BCryptPasswordEncoder(12).encode(PASSWORD), role.wire(), "en");
            UUID[] sites = site == null ? new UUID[0] : new UUID[] {site};
            jdbc.update(connection -> {
                var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, user);
                ps.setString(3, role.wire());
                ps.setArray(4, connection.createArrayOf("uuid", sites));
                ps.setArray(5, connection.createArrayOf("uuid", teamOf == null ? new UUID[0] : new UUID[] {teamOf}));
                return ps;
            });
            if (teamOf != null) jdbc.update("INSERT INTO team_member (team_id, user_id) SELECT id, ? FROM team WHERE service_group_id = ?", user, teamOf);
            MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
            assertThat(status(result)).as(body(result)).isEqualTo(200);
            return new Staff(user, JsonPath.read(body(result), "$.access_token"));
        } finally {
            clock.set(testTime);
        }
    }

    private UUID issue(UUID service, UUID priorityClass, UUID visitorId, String channel) {
        return issuance.issue(new IssueCommand(service, channel, UUID.randomUUID(), ActorType.SYSTEM, null, priorityClass, visitorId, false, null, null, null)).id();
    }

    private UUID openSession(Staff agent, UUID counter) throws Exception {
        MvcResult result = call(post("/api/v1/sessions"), agent.token(), "{\"counter_id\":\"" + counter + "\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(201);
        return UUID.fromString(field(result, "$.id"));
    }

    private void callAndServe(Staff agent, UUID session) throws Exception {
        assertThat(status(call(post("/api/v1/sessions/" + session + "/next"), agent.token(), null))).isEqualTo(200);
        assertThat(status(call(post("/api/v1/sessions/" + session + "/serve"), agent.token(), null))).isEqualTo(200);
    }

    private void complete(Staff agent, UUID session, UUID outcome) throws Exception {
        MvcResult result = call(post("/api/v1/sessions/" + session + "/complete"), agent.token(), "{\"outcome_code_id\":\"" + outcome + "\",\"note\":\"done\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(200);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andReturn();
    }

    private MvcResult run(Staff who, String key, String json) throws Exception {
        return call(post("/api/v1/reports/" + key + "/run"), who == null ? null : who.token(), json == null ? "{}" : json);
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

    // ---- the reporting store never leaks live rows, and picks them up once refreshed (FR-RPT-020) -----------------

    @Test
    void theReportReflectsOnlyTheReportingStoreAndPicksUpAFreshlyCompletedTicketOnceRefreshed() throws Exception {
        World w = world();
        UUID visitorId = visitor("V-100", "Rahim", "vip");
        UUID counter = counter(w.zone(), w.service(), "1");
        UUID outcome = newOutcome(w.service(), "RESOLVED");
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        UUID ticket = issue(w.service(), null, visitorId, Channels.RECEPTION);
        UUID session = openSession(agent, counter);
        clock.set(BASE.plusSeconds(120));
        callAndServe(agent, session);
        clock.set(BASE.plusSeconds(300));
        complete(agent, session, outcome);

        MvcResult beforeRefresh = run(admin, "detailed-token", "{\"site_id\":\"" + w.site() + "\"}");
        assertThat(status(beforeRefresh)).as(body(beforeRefresh)).isEqualTo(200);
        assertThat((List<String>) field(beforeRefresh, "$.rows[*].ticket_id"))
                .as("a report never reads the live ticket table directly")
                .doesNotContain(ticket.toString());

        scheduler.tick();

        MvcResult afterRefresh = run(admin, "detailed-token", "{\"site_id\":\"" + w.site() + "\"}");
        assertThat(status(afterRefresh)).as(body(afterRefresh)).isEqualTo(200);
        assertThat((Integer) field(afterRefresh, "$.total_rows")).isEqualTo(1);
        assertThat((Integer) field(afterRefresh, "$.tickets_issued")).isEqualTo(1);
        Map<String, Object> row = ((List<Map<String, Object>>) field(afterRefresh, "$.rows")).get(0);
        assertThat(row.get("ticket_id")).isEqualTo(ticket.toString());
        assertThat(row.get("visitor_code")).isEqualTo("V-100");
        assertThat(row.get("visitor_name")).isEqualTo("Rahim");
        assertThat(row.get("visitor_category")).isEqualTo("vip");
        assertThat(((Map<String, Object>) row.get("service_group")).get("en")).isEqualTo("Outpatient");
        assertThat(((Map<String, Object>) row.get("service")).get("en")).isEqualTo("Consultation");
        assertThat(row.get("channel")).isEqualTo("reception");
        assertThat(row.get("counter")).isEqualTo("1");
        assertThat(row.get("agent")).isEqualTo("agent");
        assertThat(((Map<String, Object>) row.get("outcome")).get("en")).isEqualTo("RESOLVED");
        assertThat(row.get("issue_time")).isNotNull();
        assertThat(row.get("call_time")).isNotNull();
        assertThat(row.get("start_time")).isNotNull();
        assertThat(row.get("end_time")).isNotNull();
        assertThat(((Number) row.get("wait_seconds")).intValue()).isEqualTo(120);
        assertThat(((Number) row.get("service_seconds")).intValue()).isEqualTo(180);
        assertThat(row.get("transfers")).isEqualTo(0);
    }

    // ---- "tickets issued" counts chain heads, and transfers is the hop depth (§18.5, ADR-0006) -------------------

    @Test
    void ticketsIssuedCountsChainHeadsAndTransfersIsTheHopDepth() throws Exception {
        World w = world();
        UUID target = newService(w.group(), "B", "Laboratory");
        UUID counterA = counter(w.zone(), w.service(), "1");
        counter(w.zone(), target, "2");
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        UUID head = issue(w.service(), null, null, Channels.RECEPTION);
        UUID session = openSession(agent, counterA);
        callAndServe(agent, session);
        MvcResult transferred = call(
                post("/api/v1/tickets/" + head + "/transfer"),
                agent.token(),
                "{\"service_id\":\"" + target + "\",\"note\":\"needs a lab test\"}");
        assertThat(status(transferred)).as(body(transferred)).isEqualTo(200);
        UUID successor = UUID.fromString((String) field(transferred, "$.successor.id"));

        scheduler.tick();

        MvcResult report = run(admin, "detailed-token", "{\"site_id\":\"" + w.site() + "\"}");
        assertThat(status(report)).as(body(report)).isEqualTo(200);
        assertThat((Integer) field(report, "$.total_rows")).as("both the predecessor and the successor are rows").isEqualTo(2);
        assertThat((Integer) field(report, "$.tickets_issued")).as("one visit, one chain head").isEqualTo(1);

        List<Map<String, Object>> rows = field(report, "$.rows");
        Map<String, Object> headRow = rows.stream().filter(r -> head.toString().equals(r.get("ticket_id"))).findFirst().orElseThrow();
        Map<String, Object> successorRow = rows.stream().filter(r -> successor.toString().equals(r.get("ticket_id"))).findFirst().orElseThrow();
        assertThat(headRow.get("transfers")).isEqualTo(0);
        assertThat(successorRow.get("transfers")).isEqualTo(1);
    }

    // ---- FR-RPT-001: every filter narrows the report --------------------------------------------------------------

    @Test
    void everyFilterOfFrRpt001NarrowsTheReport() throws Exception {
        World w = world();
        UUID counterA = counter(w.zone(), w.service(), "1");
        // A different Service in the same group, so calling next at counterA can never draw `early` instead of `matching`.
        UUID otherService = newService(w.group(), "Z", "Archive");
        UUID urgent = newClass("Urgent");
        UUID visitorId = visitor("V-1", "Karim", "senior");
        Staff agentA = staff(Role.AGENT, w.site(), w.group());
        Staff agentB = staff(Role.AGENT, w.site(), w.group());
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        clock.set(BASE.minus(Duration.ofDays(2)));
        UUID early = issue(otherService, null, null, Channels.KIOSK);
        clock.set(BASE);
        UUID matching = issue(w.service(), urgent, visitorId, Channels.RECEPTION);
        UUID sessionA = openSession(agentA, counterA);
        callAndServe(agentA, sessionA);

        scheduler.tick();

        record Case(String label, String body, boolean expectMatching) {}
        List<Case> cases = List.of(
                new Case("date range excludes the early ticket", "{\"from\":\"" + BASE.minus(Duration.ofHours(1)) + "\",\"to\":\"" + BASE.plusSeconds(3600) + "\"}", true),
                new Case("zone", "{\"zone_id\":\"" + w.zone() + "\"}", true),
                new Case("service group", "{\"service_group_id\":\"" + w.group() + "\"}", true),
                new Case("service", "{\"service_id\":\"" + w.service() + "\"}", true),
                new Case("agent", "{\"agent_id\":\"" + agentA.id() + "\"}", true),
                new Case("agent not matching", "{\"agent_id\":\"" + agentB.id() + "\"}", false),
                new Case("priority class", "{\"priority_class_id\":\"" + urgent + "\"}", true),
                new Case("channel", "{\"channel\":\"reception\"}", true),
                new Case("channel not matching", "{\"channel\":\"kiosk\", \"agent_id\":\"" + agentA.id() + "\"}", false),
                new Case("visitor category", "{\"visitor_category\":\"senior\"}", true));

        for (Case c : cases) {
            MvcResult result = run(admin, "detailed-token", c.body());
            assertThat(status(result)).as(c.label() + ": " + body(result)).isEqualTo(200);
            List<String> ids = field(result, "$.rows[*].ticket_id");
            if (c.expectMatching()) {
                assertThat(ids).as(c.label()).contains(matching.toString());
            } else {
                assertThat(ids).as(c.label()).doesNotContain(matching.toString());
            }
        }
        assertThat((Integer) field(run(admin, "detailed-token", "{}"), "$.total_rows")).as("no filter reaches both").isEqualTo(2);
        assertThat(early).isNotNull();
    }

    // ---- FR-RPT-002: server-side paging and sort by any displayed column -------------------------------------------

    @Test
    void pagesAndSortsByAnyDisplayedColumn() throws Exception {
        World w = world();
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);
        clock.set(BASE.minus(Duration.ofMinutes(20)));
        UUID first = issue(w.service(), null, null, Channels.RECEPTION);
        clock.set(BASE.minus(Duration.ofMinutes(10)));
        UUID second = issue(w.service(), null, null, Channels.RECEPTION);
        clock.set(BASE);
        UUID third = issue(w.service(), null, null, Channels.RECEPTION);

        scheduler.tick();

        MvcResult firstPage = run(admin, "detailed-token", "{\"site_id\":\"" + w.site() + "\", \"sort\":\"issued_at\", \"direction\":\"asc\", \"page\":0, \"size\":2}");
        assertThat(status(firstPage)).as(body(firstPage)).isEqualTo(200);
        assertThat((List<String>) field(firstPage, "$.rows[*].ticket_id")).containsExactly(first.toString(), second.toString());
        assertThat((Integer) field(firstPage, "$.total_pages")).isEqualTo(2);

        MvcResult secondPage = run(admin, "detailed-token", "{\"site_id\":\"" + w.site() + "\", \"sort\":\"issued_at\", \"direction\":\"asc\", \"page\":1, \"size\":2}");
        assertThat((List<String>) field(secondPage, "$.rows[*].ticket_id")).containsExactly(third.toString());

        MvcResult descending = run(admin, "detailed-token", "{\"site_id\":\"" + w.site() + "\", \"sort\":\"issued_at\", \"direction\":\"desc\", \"size\":50}");
        assertThat((List<String>) field(descending, "$.rows[*].ticket_id")).containsExactly(third.toString(), second.toString(), first.toString());

        MvcResult badSort = run(admin, "detailed-token", "{\"sort\":\"not_a_column\"}");
        assertThat(status(badSort)).isEqualTo(400);
    }

    // ---- POST /reports/{key}/run: permission, scope, and an unknown key --------------------------------------------

    @Test
    void permissionScopeAndUnknownKey() throws Exception {
        World w = world();
        Staff sysAdmin = staff(Role.SYSTEM_ADMIN, null, null);
        Staff orgAdmin = staff(Role.ORG_ADMIN, w.site(), null);
        Staff teamAdmin = staff(Role.TEAM_ADMIN, w.site(), w.group());
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        Staff reception = staff(Role.RECEPTION_OPERATOR, w.site(), null);

        assertThat(status(run(sysAdmin, "detailed-token", "{}"))).isEqualTo(200);
        assertThat(status(run(orgAdmin, "detailed-token", "{}"))).isEqualTo(200);
        assertThat(status(run(teamAdmin, "detailed-token", "{}"))).isEqualTo(200);
        assertThat(status(run(agent, "detailed-token", "{}"))).as("an Agent's own reach does not cover running reports").isEqualTo(403);
        assertThat(status(run(reception, "detailed-token", "{}"))).isEqualTo(403);
        assertThat(status(run(null, "detailed-token", "{}"))).isEqualTo(401);

        World other = world();
        assertThat(status(run(orgAdmin, "detailed-token", "{\"site_id\":\"" + other.site() + "\"}")))
                .as("another Site outside the caller's own scope").isEqualTo(403);

        assertThat(status(run(orgAdmin, "no-such-report", "{}"))).as("an unknown report key").isEqualTo(404);
    }
}
