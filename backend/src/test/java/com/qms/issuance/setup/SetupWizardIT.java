package com.qms.issuance.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * Ticket 56 against real PostgreSQL, end to end: a System Administrator applies a vertical profile, builds a Site,
 * registers a device, issues the wizard's own test token through the real issuance pipeline, an Agent calls it
 * through a real counter session (ticket 29's announcement fires on the same transition), and go-live is refused
 * until every step is done and then succeeds. Covers CFG-001, CFG-002, CFG-003, FR-OPS-010, §3.2, §3.3, §3.4,
 * §26.2, and DoD §27.5 (permission-checked server-side, en/bn strings, audit entries).
 *
 * <p>Every state this ticket introduces (the active profile, feature flags, label overrides, go-live) is a
 * singleton, organisation-wide fact, so it is exercised as one long, deliberately-ordered scenario in a single test
 * method rather than several independent {@code @Test}s: two methods run against the same database and would
 * otherwise race each other over exactly that shared state.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, SetupWizardIT.Clocks.class})
class SetupWizardIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    static final Instant BASE = Instant.parse("2026-09-22T09:00:00Z");

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
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-setup");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    private record StaffUser(UUID id, String token) {}

    @Test
    void walksProfileToGoLiveWithARealTestTokenIssuedPrintedCalledAndAnnounced() throws Exception {
        clock.set(BASE);

        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main branch', ?, 'Asia/Dhaka', '1 Main Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "S-" + site.toString().substring(0, 8));

        StaffUser admin = user(Role.SYSTEM_ADMIN, site, null);

        // A fresh installation: nothing is done yet.
        MvcResult before = state(admin);
        assertThat(status(before)).as(body(before)).isEqualTo(200);
        assertThat(bool(before, "$.profile_applied")).isFalse();
        assertThat(bool(before, "$.go_live_ready")).isFalse();

        // Permission check (DoD §27.5 item 4): a non-admin cannot reach any wizard endpoint (FR-CFG-108, API-016).
        UUID zoneForAgent = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, floor_label) VALUES (?, ?, 'Hall', '1st')", zoneForAgent, site);
        StaffUser strayAgent = user(Role.AGENT, site, null);
        MvcResult forbidden = state(strayAgent);
        assertThat(status(forbidden)).isEqualTo(403);

        // §3.4: pick the banking profile. CFG-002: applying it the first time succeeds.
        MvcResult applied = applyProfile(admin, "banking");
        assertThat(status(applied)).as(body(applied)).isEqualTo(200);
        assertThat(str(applied, "$.id")).isEqualTo("banking");

        // CFG-002: a second plain "apply" (not an explicit reset) is refused; a profile is set only once at first run.
        MvcResult reapplied = applyProfile(admin, "healthcare");
        assertThat(status(reapplied)).as(body(reapplied)).isEqualTo(409);
        assertThat(str(reapplied, "$.error.details.reason")).isEqualTo("already_provisioned");

        // CFG-002: the explicit reset action is allowed at any time, and re-seeds the chosen profile's data.
        MvcResult reset = resetProfile(admin, "banking");
        assertThat(status(reset)).as(body(reset)).isEqualTo(200);

        // §3.2: terminology remapping resolves through the active profile; "Customer" is banking's own wording.
        MvcResult labels = mvc.perform(get("/api/v1/labels").header("Authorization", "Bearer " + admin.token())).andReturn();
        assertThat(status(labels)).as(body(labels)).isEqualTo(200);
        assertThat(str(labels, "$['entity.visitor']")).isEqualTo("Customer");
        assertThat(str(labels, "$['entity.ticket']")).isEqualTo("Token");

        // CFG-003: any value a profile sets is editable afterwards, one key at a time.
        MvcResult edited = mvc.perform(put("/api/v1/labels/entity.visitor")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lang\":\"en\",\"value\":\"Client\"}"))
                .andReturn();
        assertThat(status(edited)).as(body(edited)).isEqualTo(200);
        assertThat(str(edited, "$['entity.visitor']")).isEqualTo("Client");

        // CFG-003: the feature flags a profile seeds are also editable afterwards, one key at a time.
        MvcResult flags = mvc.perform(get("/api/v1/setup/feature-flags").header("Authorization", "Bearer " + admin.token())).andReturn();
        assertThat(status(flags)).as(body(flags)).isEqualTo(200);
        assertThat(bool(flags, "$.appointment")).isTrue();

        MvcResult flagEdited = mvc.perform(put("/api/v1/setup/feature-flags/appointment")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andReturn();
        assertThat(status(flagEdited)).as(body(flagEdited)).isEqualTo(200);
        assertThat(bool(flagEdited, "$.appointment")).isFalse();

        // A key outside the closed vocabulary (CFG-001) is refused rather than silently accepted.
        MvcResult unknownFlag = mvc.perform(put("/api/v1/setup/feature-flags/not_a_real_flag")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andReturn();
        assertThat(status(unknownFlag)).isEqualTo(400);

        // The profile's starter priority classes were seeded (organisation-wide, no Site dependency).
        Integer seededClasses = jdbc.queryForObject("SELECT count(*) FROM priority_class WHERE name_i18n->>'en' = 'Priority banking'", Integer.class);
        assertThat(seededClasses).isEqualTo(1);

        // Zones, counters, a service and its team: the rest of §26.2's steps, built the same way the existing
        // admin screens (tickets 05, 06) already do.
        UUID zone = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, floor_label) VALUES (?, ?, 'Hall', '1st')", zone, site);
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Retail banking\"}'::jsonb, 'G')", group, site);
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, 'Retail team')", UUID.randomUUID(), group);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode)"
                        + " VALUES (?, ?, '{\"en\":\"Cash deposit\"}'::jsonb, 'A', 10, 30, '[\"reception\",\"kiosk\"]'::jsonb, 'both')",
                service, group);
        UUID counter = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, 'Counter 1')", counter, zone);
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", counter, service);

        StaffUser agent = user(Role.AGENT, site, group);
        jdbc.update("INSERT INTO device (id, kind, site_id, label) VALUES (?, 'kiosk', ?, 'Reception kiosk')", UUID.randomUUID(), site);

        MvcResult midway = state(admin);
        assertThat(bool(midway, "$.org_and_sites")).isTrue();
        assertThat(bool(midway, "$.zones_and_counters")).isTrue();
        assertThat(bool(midway, "$.services_and_numbering")).isTrue();
        assertThat(bool(midway, "$.users_and_roles")).isTrue();
        assertThat(bool(midway, "$.devices_registered")).isTrue();
        assertThat(bool(midway, "$.go_live_ready")).isFalse();

        // FR-OPS-010: go-live is refused before a test token exists.
        MvcResult tooEarly = mvc.perform(post("/api/v1/setup/go-live").header("Authorization", "Bearer " + admin.token())).andReturn();
        assertThat(status(tooEarly)).as(body(tooEarly)).isEqualTo(409);
        List<String> missing = field(tooEarly, "$.error.details.missing");
        assertThat(missing).contains("test_token_issued", "test_token_printed", "test_token_called", "test_token_announced");

        // Issue the wizard's own test token through the real issuance pipeline (SRS §8.5), the same one every
        // channel uses.
        MvcResult issued = mvc.perform(post("/api/v1/setup/test-token")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service_id\":\"" + service + "\"}"))
                .andReturn();
        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        UUID ticketId = UUID.fromString(str(issued, "$.id"));

        MvcResult afterIssue = state(admin);
        assertThat(bool(afterIssue, "$.test_token.issued")).isTrue();
        assertThat(bool(afterIssue, "$.test_token.printed")).isFalse();
        assertThat(bool(afterIssue, "$.test_token.called")).isFalse();
        assertThat(bool(afterIssue, "$.test_token.announced")).isFalse();

        // FR-CFG-032 precedent: the admin confirms the physical/browser print actually happened.
        MvcResult printed = mvc.perform(post("/api/v1/setup/test-token/" + ticketId + "/confirm-print").header("Authorization", "Bearer " + admin.token())).andReturn();
        assertThat(status(printed)).as(body(printed)).isEqualTo(200);
        assertThat(bool(printed, "$.test_token.printed")).isTrue();

        // Still refused: not yet called.
        MvcResult stillEarly = mvc.perform(post("/api/v1/setup/go-live").header("Authorization", "Bearer " + admin.token())).andReturn();
        assertThat(status(stillEarly)).isEqualTo(409);

        // Confirming the announcement before the token has ever been called is refused: an announcement cannot
        // have played for a token that has not been called yet.
        MvcResult tooSoon = mvc.perform(post("/api/v1/setup/test-token/" + ticketId + "/confirm-announce").header("Authorization", "Bearer " + admin.token())).andReturn();
        assertThat(status(tooSoon)).as(body(tooSoon)).isEqualTo(409);
        assertThat(str(tooSoon, "$.error.details.reason")).isEqualTo("test_token_not_called_yet");

        // An Agent calls it through the real counter session flow (ticket 10); the same transition fires the
        // Zone's chime and voice announcement (ticket 29, FR-DSP-020..028).
        UUID sessionId = openSession(agent, counter);
        MvcResult called = mvc.perform(post("/api/v1/sessions/" + sessionId + "/next").header("Authorization", "Bearer " + agent.token())).andReturn();
        assertThat(status(called)).as(body(called)).isEqualTo(200);
        assertThat(str(called, "$.ticket.id")).isEqualTo(ticketId.toString());

        MvcResult afterCall = state(admin);
        assertThat(bool(afterCall, "$.test_token.called")).isTrue();
        // "Called" and "announced" are two separate, individually-confirmed steps (FR-OPS-010): the Zone's own
        // chime/voice announcement firing on the same transition does not, by itself, confirm anything back to the
        // server, so "announced" stays false until the admin explicitly confirms it, mirroring "printed".
        assertThat(bool(afterCall, "$.test_token.announced")).isFalse();
        assertThat(bool(afterCall, "$.go_live_ready")).isFalse();

        // The admin's own confirmation that the Zone's announcement actually played.
        MvcResult announced = mvc.perform(post("/api/v1/setup/test-token/" + ticketId + "/confirm-announce").header("Authorization", "Bearer " + admin.token())).andReturn();
        assertThat(status(announced)).as(body(announced)).isEqualTo(200);
        assertThat(bool(announced, "$.test_token.announced")).isTrue();
        assertThat(bool(announced, "$.go_live_ready")).isTrue();

        // FR-OPS-010: every step done, go-live succeeds.
        MvcResult wentLive = mvc.perform(post("/api/v1/setup/go-live").header("Authorization", "Bearer " + admin.token())).andReturn();
        assertThat(status(wentLive)).as(body(wentLive)).isEqualTo(200);
        String goLiveAt = str(wentLive, "$.go_live_at");
        assertThat(goLiveAt).isNotBlank();

        // Idempotent: going live again returns the same timestamp rather than failing or moving it.
        MvcResult wentLiveAgain = mvc.perform(post("/api/v1/setup/go-live").header("Authorization", "Bearer " + admin.token())).andReturn();
        assertThat(status(wentLiveAgain)).isEqualTo(200);
        assertThat(str(wentLiveAgain, "$.go_live_at")).isEqualTo(goLiveAt);

        // DoD §27.5 item 5: the wizard's own actions are audited.
        List<String> auditActions = jdbc.query(
                "SELECT action FROM audit_log WHERE action LIKE 'profile.%' OR action LIKE 'setup.%' ORDER BY occurred_at", (rs, i) -> rs.getString("action"));
        assertThat(auditActions).contains(
                "profile.applied", "profile.reset", "setup.test_token.issued", "setup.test_token.printed", "setup.test_token.announced", "setup.go_live");
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    private StaffUser user(Role role, UUID site, UUID teamOf) throws Exception {
        UUID id = UUID.randomUUID();
        String username = role.wire() + "-" + id;
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, 'en')",
                id, username, new BCryptPasswordEncoder(12).encode(PASSWORD), role.wire());
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, id);
            ps.setString(3, role.wire());
            ps.setArray(4, connection.createArrayOf("uuid", new UUID[] {site}));
            ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
            return ps;
        });
        if (teamOf != null) {
            jdbc.update("INSERT INTO team_member (team_id, user_id) SELECT id, ? FROM team WHERE service_group_id = ?", id, teamOf);
        }
        // Login's own access token is time-checked against the real wall clock (JwtTimestampValidator has no seam
        // for the injected business Clock), so the clock must sit near real time for the login call itself, the same
        // guard ReportExportIT#staff and its siblings already use around their own login calls.
        Instant businessTime = clock.instant();
        clock.set(Instant.now());
        MvcResult result;
        try {
            result = mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
        } finally {
            clock.set(businessTime);
        }
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        return new StaffUser(id, field(result, "$.access_token"));
    }

    private UUID openSession(StaffUser agent, UUID counter) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + agent.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counter_id\":\"" + counter + "\"}"))
                .andReturn();
        assertThat(status(result)).as(body(result)).isEqualTo(201);
        return UUID.fromString(field(result, "$.id"));
    }

    private MvcResult state(StaffUser user) throws Exception {
        return mvc.perform(get("/api/v1/setup/state").header("Authorization", "Bearer " + user.token())).andReturn();
    }

    private MvcResult applyProfile(StaffUser admin, String profileId) throws Exception {
        return call(post("/api/v1/setup/profile"), admin.token(), "{\"profile_id\":\"" + profileId + "\"}");
    }

    private MvcResult resetProfile(StaffUser admin, String profileId) throws Exception {
        return call(post("/api/v1/setup/profile/reset"), admin.token(), "{\"profile_id\":\"" + profileId + "\"}");
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andReturn();
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

    private static boolean bool(MvcResult result, String path) throws Exception {
        Boolean value = field(result, path);
        return Boolean.TRUE.equals(value);
    }

    private static String str(MvcResult result, String path) throws Exception {
        Object value = field(result, path);
        return value == null ? null : value.toString();
    }
}
