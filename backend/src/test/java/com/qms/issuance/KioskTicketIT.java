package com.qms.issuance;

import static org.assertj.core.api.Assertions.assertThat;
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
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\",\"bn\":\"পরামর্শ\"}'::jsonb, ?, 10, 30, ?::jsonb, 'both', ?)",
                id, group, prefix, channelsJson, active);
        return id;
    }

    private void link(UUID counter, UUID service) {
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", counter, service);
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
        MockHttpServletRequestBuilder request = post("/api/v1/kiosk/tickets");
        if (key != null) request.header("Idempotency-Key", key);
        return call(request, token, "{\"service_id\":\"" + service + "\"}");
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
