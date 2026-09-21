package com.qms.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Ticket 46 against real PostgreSQL (SRS §15.1): {@code GET /dashboard/live} answers every FR-MON-003 tile plus
 * FR-QUE-033's served-per-counter view, filterable and shareable as a URL (FR-MON-002), scope-limited to all groups
 * for an Org Admin and only the caller's own otherwise (§5.2), and {@code POST /dashboard/{site}/staff-alert} is the
 * one supervisor act FR-MON-004 adds beyond what earlier tickets already expose.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, DashboardIT.Clocks.class})
class DashboardIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    /** Friday 19 September 2026, 16:00 in Dhaka (UTC+6) — the same "today" fixture ticket 18's own IT uses. */
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
        registry.add("qms.dashboard.refresh-cron", () -> "-");
        // This ticket seeds appointment rows directly against the test clock; the real sweeps must not also touch them.
        registry.add("qms.appointment.hold-expiry-check-cron", () -> "-");
        registry.add("qms.appointment.reminder-check-cron", () -> "-");
        registry.add("qms.appointment.no-show-check-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-dashboard");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssuanceService issuance;
    @Autowired MutableClock clock;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
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
                site, "D-" + site.toString().substring(0, 8));
        UUID zone = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, floor_label) VALUES (?, ?, 'Hall', '1st')", zone, site);
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'G')", group, site);
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, 'Outpatient team')", UUID.randomUUID(), group);
        return new World(site, zone, group, newService(group, "A", 30));
    }

    private UUID newService(UUID group, String prefix, int slaWaitMinutes) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, ?, 10, ?, '[\"reception\",\"kiosk\"]'::jsonb, 'both')",
                id, group, prefix, slaWaitMinutes);
        return id;
    }

    private UUID counter(UUID zone, UUID service, String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, ?)", id, zone, label);
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", id, service);
        return id;
    }

    private UUID newClass(String name, int maxWaitMinutes) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO priority_class (id, name_i18n, headstart_minutes, max_wait_minutes, created_at, updated_at) VALUES (?, ?::jsonb, 0, ?, now(), now())",
                id, "{\"en\":\"" + name + "\"}", maxWaitMinutes);
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

    /** Issues a ticket as if {@code minutesAgo} minutes before BASE, then puts the clock back at BASE. */
    private UUID issueAgo(UUID service, int minutesAgo) {
        clock.set(BASE.minus(Duration.ofMinutes(minutesAgo)));
        try {
            return issuance.issue(new IssueCommand(service, Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null)).id();
        } finally {
            clock.set(BASE);
        }
    }

    private void terminal(UUID ticket, String state, String eventType) {
        jdbc.update("UPDATE ticket SET state = ?, closed_at = now(), version = version + 1 WHERE id = ?", state, ticket);
        int seq = nextSeq(ticket);
        jdbc.update(
                "INSERT INTO ticket_event (id, ticket_id, seq, event_type, from_state, to_state, actor_type, occurred_at, recorded_at)"
                        + " VALUES (?, ?, ?, ?, 'waiting', ?, 'staff', now(), now())",
                UUID.randomUUID(), ticket, seq, eventType, state);
    }

    private int nextSeq(UUID ticket) {
        Integer max = jdbc.queryForObject("SELECT max(seq) FROM ticket_event WHERE ticket_id = ?", Integer.class, ticket);
        return (max == null ? 0 : max) + 1;
    }

    private UUID openSession(Staff agent, UUID counter) throws Exception {
        MvcResult result = call(post("/api/v1/sessions"), agent.token(), "{\"counter_id\":\"" + counter + "\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(201);
        return UUID.fromString(field(result, "$.id"));
    }

    private void appointment(UUID service, String state, java.time.LocalDate slotDate, java.time.LocalTime start) {
        UUID visitor = UUID.randomUUID();
        jdbc.update("INSERT INTO visitor (id, name, created_at) VALUES (?, 'Visitor', now())", visitor);
        jdbc.update(
                "INSERT INTO appointment (id, reference_code, service_id, visitor_id, slot_date, slot_start, slot_end, state, source, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'staff', now(), now())",
                UUID.randomUUID(), "REF-" + UUID.randomUUID().toString().substring(0, 8), service, visitor, slotDate, start, start.plusMinutes(15), state);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andReturn();
    }

    private MvcResult live(Staff who, UUID site, String queryString) throws Exception {
        return call(get("/api/v1/dashboard/live?site_id=" + site + (queryString == null ? "" : "&" + queryString)), who == null ? null : who.token(), null);
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

    // ---- FR-MON-002: filter, scope, shareable as a URL ------------------------------------------------------------

    @Test
    void theFilterRoundTripsAndSiteIsMandatory() throws Exception {
        World w = world();
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        MvcResult ok = live(admin, w.site(), "zone_id=" + w.zone() + "&service_group_id=" + w.group() + "&service_id=" + w.service());
        assertThat(status(ok)).as(body(ok)).isEqualTo(200);
        assertThat((String) field(ok, "$.site_id")).isEqualTo(w.site().toString());
        assertThat((String) field(ok, "$.zone_id")).isEqualTo(w.zone().toString());
        assertThat((String) field(ok, "$.service_group_id")).isEqualTo(w.group().toString());
        assertThat((String) field(ok, "$.service_id")).isEqualTo(w.service().toString());

        assertThat(status(call(get("/api/v1/dashboard/live"), admin.token(), null))).as("Site is mandatory").isEqualTo(400);
        assertThat(status(live(null, w.site(), null))).as("no token").isEqualTo(401);
        assertThat(status(live(admin, w.site(), "zone_id=" + UUID.randomUUID()))).as("that Zone is not this Site's").isEqualTo(404);

        World other = world();
        Staff elsewhere = staff(Role.ORG_ADMIN, other.site(), null);
        assertThat(status(live(elsewhere, w.site(), null))).as("outside their own Site scope: scope is checked before existence (FR-CFG-106)").isEqualTo(403);

        // A caller unrestricted on the Site dimension (an empty claim) reaches the existence check itself.
        Staff unrestricted = staff(Role.SYSTEM_ADMIN, null, null);
        assertThat(status(live(unrestricted, UUID.randomUUID(), null))).as("no such Site").isEqualTo(404);
    }

    @Test
    void anOrgAdminSeesEveryGroupATeamAdminOnlyTheirOwn() throws Exception {
        World w = world();
        UUID otherGroup = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Other\"}'::jsonb, 'H')", otherGroup, w.site());
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, 'Other team')", UUID.randomUUID(), otherGroup);
        UUID otherService = newService(otherGroup, "B", 30);
        issueAgo(w.service(), 5);
        issueAgo(otherService, 5);

        Staff orgAdmin = staff(Role.ORG_ADMIN, w.site(), null);
        MvcResult all = live(orgAdmin, w.site(), null);
        assertThat(status(all)).as(body(all)).isEqualTo(200);
        assertThat((List<String>) field(all, "$.waiting_now[*].service_group_id")).containsExactlyInAnyOrder(w.group().toString(), otherGroup.toString());

        Staff teamAdmin = staff(Role.TEAM_ADMIN, w.site(), w.group());
        MvcResult own = live(teamAdmin, w.site(), null);
        assertThat(status(own)).as(body(own)).isEqualTo(200);
        assertThat((List<String>) field(own, "$.waiting_now[*].service_group_id")).containsExactly(w.group().toString());

        assertThat(status(live(teamAdmin, w.site(), "service_group_id=" + otherGroup))).as("named outside their scope").isEqualTo(403);
    }

    // ---- FR-MON-003 tiles ---------------------------------------------------------------------------------------

    @Test
    void waitingNowCountsAndLongestWaitPerGroup() throws Exception {
        World w = world();
        issueAgo(w.service(), 20);
        issueAgo(w.service(), 5);
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        MvcResult result = live(admin, w.site(), null);
        assertThat((Integer) field(result, "$.waiting_now[0].count")).isEqualTo(2);
        assertThat(((Number) field(result, "$.waiting_now[0].longest_wait_seconds")).longValue()).isCloseTo(1200L, org.assertj.core.data.Offset.offset(5L));
    }

    @Test
    void longestWaitsFlagsEscalatedAndSlaBreached() throws Exception {
        World w = world();
        UUID urgent = newClass("Urgent", 5);
        clock.set(BASE.minus(Duration.ofMinutes(10)));
        UUID escalated = issuance.issue(new IssueCommand(w.service(), Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null, urgent)).id();
        clock.set(BASE);
        UUID slaBreached = issueAgo(w.service(), 40);
        UUID fine = issueAgo(w.service(), 2);
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        MvcResult result = live(admin, w.site(), null);
        assertThat((List<String>) field(result, "$.longest_waits[*].ticket_id"))
                .as("ordered by wait, longest first: 40 min, then 10 min (past only its class's short max wait), then 2 min")
                .containsExactly(slaBreached.toString(), escalated.toString(), fine.toString());
        assertThat((Boolean) field(result, "$.longest_waits[0].sla_breached")).as("past the Service's own 30-minute SLA").isTrue();
        assertThat((Boolean) field(result, "$.longest_waits[0].escalated")).as("no Priority class of its own to escalate against").isFalse();
        assertThat((Boolean) field(result, "$.longest_waits[1].escalated")).as("past its class's own 5-minute max wait").isTrue();
        assertThat((Boolean) field(result, "$.longest_waits[1].sla_breached")).as("under the Service's own 30-minute SLA").isFalse();
        assertThat((Boolean) field(result, "$.longest_waits[2].escalated")).isFalse();
        assertThat((Boolean) field(result, "$.longest_waits[2].sla_breached")).isFalse();
    }

    @Test
    void servingNowAndCountersAndServedPerOpenCounter() throws Exception {
        World w = world();
        UUID counterA = counter(w.zone(), w.service(), "1");
        UUID counterB = counter(w.zone(), w.service(), "2");
        counter(w.zone(), w.service(), "3");
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        UUID ticket = issueAgo(w.service(), 3);
        UUID sessionA = openSession(agent, counterA);
        assertThat(status(call(post("/api/v1/sessions/" + sessionA + "/next"), agent.token(), null))).isEqualTo(200);
        assertThat(status(call(post("/api/v1/sessions/" + sessionA + "/serve"), agent.token(), null))).isEqualTo(200);

        Staff agentB = staff(Role.AGENT, w.site(), w.group());
        openSession(agentB, counterB);

        MvcResult result = live(admin, w.site(), null);
        assertThat((String) field(result, "$.serving_now[0].ticket_id")).isEqualTo(ticket.toString());
        assertThat((String) field(result, "$.serving_now[0].counter_id")).isEqualTo(counterA.toString());
        assertThat((Map<String, Object>) field(result, "$.counters")).containsEntry("open", 2).containsEntry("closed", 1);

        UUID served = issueAgo(w.service(), 1);
        jdbc.update("UPDATE ticket SET state = 'completed', counter_id = ?, closed_at = now() WHERE id = ?", counterA, served);
        MvcResult withThroughput = live(admin, w.site(), null);
        assertThat((List<Map<String, Object>>) field(withThroughput, "$.served_per_open_counter"))
                .filteredOn(row -> counterA.toString().equals(row.get("counter_id")))
                .extracting(row -> row.get("served_count"))
                .containsExactly(1);
    }

    @Test
    void throughputTodayCountsTerminalTransitionsSinceTheStartOfTheDay() throws Exception {
        World w = world();
        terminal(issueAgo(w.service(), 5), "completed", "ticket.completed");
        terminal(issueAgo(w.service(), 5), "cancelled", "ticket.cancelled");
        terminal(issueAgo(w.service(), 5), "no_show", "ticket.no_show");
        terminal(issueAgo(w.service(), 5), "transferred", "ticket.transferred");
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        MvcResult result = live(admin, w.site(), null);
        assertThat((Map<String, Object>) field(result, "$.throughput_today"))
                .containsEntry("served", 1).containsEntry("cancelled", 1).containsEntry("no_show", 1).containsEntry("transferred", 1);
    }

    @Test
    void remoteQueueBucketsByState() throws Exception {
        World w = world();
        UUID remote = issueAgo(w.service(), 5);
        jdbc.update("UPDATE ticket SET state = 'remote', origin_channel = 'mobile' WHERE id = ?", remote);
        UUID approaching = issueAgo(w.service(), 5);
        jdbc.update("UPDATE ticket SET state = 'remote', origin_channel = 'mobile', remote_hold_started_at = now() WHERE id = ?", approaching);
        UUID present = issueAgo(w.service(), 5);
        jdbc.update("UPDATE ticket SET origin_channel = 'mobile' WHERE id = ?", present);
        UUID forfeited = issueAgo(w.service(), 5);
        jdbc.update("UPDATE ticket SET state = 'forfeited', origin_channel = 'mobile' WHERE id = ?", forfeited);
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        MvcResult result = live(admin, w.site(), null);
        assertThat((Map<String, Object>) field(result, "$.remote_queue"))
                .containsEntry("remote", 1).containsEntry("approaching", 1).containsEntry("present", 1).containsEntry("forfeited", 1);
    }

    @Test
    void deviceHealthCountsOfflineAndLeavesPrintersAnEmptyState() throws Exception {
        World w = world();
        UUID onlineKiosk = UUID.randomUUID();
        jdbc.update("INSERT INTO device (id, kind, site_id, label, last_heartbeat_at) VALUES (?, 'kiosk', ?, 'K1', ?)", onlineKiosk, w.site(), java.sql.Timestamp.from(BASE));
        UUID offlineKiosk = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO device (id, kind, site_id, label, last_heartbeat_at) VALUES (?, 'kiosk', ?, 'K2', ?)",
                offlineKiosk, w.site(), java.sql.Timestamp.from(BASE.minus(Duration.ofMinutes(20))));
        UUID offlineDisplay = UUID.randomUUID();
        jdbc.update("INSERT INTO device (id, kind, site_id, zone_id, label) VALUES (?, 'display', ?, ?, 'D1')", offlineDisplay, w.site(), w.zone());
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        MvcResult result = live(admin, w.site(), null);
        Map<String, Object> health = field(result, "$.device_health");
        assertThat(health).containsEntry("kiosks_offline", 1).containsEntry("displays_offline", 1).containsKey("printers_offline");
        assertThat(health.get("printers_offline")).as("printers are not modelled yet: an empty state, not a fabricated count").isNull();
    }

    @Test
    void appointmentsTodayCountsByStateAndUpcomingNextHour() throws Exception {
        World w = world();
        java.time.LocalDate today = java.time.LocalDate.of(2026, 9, 19);
        appointment(w.service(), "booked", today, java.time.LocalTime.of(16, 30));
        appointment(w.service(), "checked_in", today, java.time.LocalTime.of(11, 0));
        appointment(w.service(), "no_show", today, java.time.LocalTime.of(9, 0));
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        MvcResult result = live(admin, w.site(), null);
        assertThat((Map<String, Object>) field(result, "$.appointments_today"))
                .containsEntry("booked", 1).containsEntry("checked_in", 1).containsEntry("no_show", 1).containsEntry("upcoming_next_hour", 1);
    }

    // ---- FR-MON-004: the one supervisor act this ticket adds --------------------------------------------------

    @Test
    void aSupervisorSendsAStaffAlertWhichIsAudited() throws Exception {
        World w = world();
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        MvcResult sent = call(post("/api/v1/dashboard/" + w.site() + "/staff-alert"), admin.token(), "{\"message\":\"Counter 3 needs help\"}");
        assertThat(status(sent)).as(body(sent)).isEqualTo(200);

        Map<String, Object> audited = jdbc.queryForMap(
                "SELECT actor_id, after::text AS after FROM audit_log WHERE action = 'dashboard.staff_alert_sent' AND entity_id = ? ORDER BY occurred_at DESC LIMIT 1", w.site());
        assertThat(audited.get("actor_id")).isEqualTo(admin.id());
        assertThat(JsonPath.<String>read((String) audited.get("after"), "$.message")).isEqualTo("Counter 3 needs help");

        assertThat(status(call(post("/api/v1/dashboard/" + w.site() + "/staff-alert"), admin.token(), "{\"message\":\"  \"}"))).as("blank message").isEqualTo(400);
        assertThat(status(call(post("/api/v1/dashboard/" + w.site() + "/staff-alert"), admin.token(), "{}"))).as("no message at all").isEqualTo(400);

        Staff agent = staff(Role.AGENT, w.site(), w.group());
        assertThat(status(call(post("/api/v1/dashboard/" + w.site() + "/staff-alert"), agent.token(), "{\"message\":\"help\"}")))
                .as("an Agent's own reach does not cover sending an alert").isEqualTo(403);

        World other = world();
        Staff elsewhere = staff(Role.ORG_ADMIN, other.site(), null);
        assertThat(status(call(post("/api/v1/dashboard/" + w.site() + "/staff-alert"), elsewhere.token(), "{\"message\":\"help\"}")))
                .as("another Site").isEqualTo(403);
    }
}
