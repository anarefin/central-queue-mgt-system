package com.qms.issuance.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.mobile.CapturingVisitorOtpMailer;
import com.qms.mobile.VisitorAuthTestSupport;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
 * Ticket 68 against real PostgreSQL: each of the six flags gates the endpoints CFG-003 names, before their own
 * finer setting, and turning one off never touches a ticket, appointment or journey already in flight. Reading is
 * open to any authenticated principal (and a device, through {@code GET /config/bootstrap}); writing is audited as
 * {@code feature_flag.updated} with its before/after value. {@link com.qms.configuration.site.MultiSiteFeatureFlagIT}
 * covers {@code multi_site} on its own, since it needs a database with no Site in it yet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, FeatureFlagsEnforcedIT.Clocks.class, VisitorAuthTestSupport.Fakes.class})
class FeatureFlagsEnforcedIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    /** Saturday 19 September 2026, 10:00 in Dhaka (UTC+6): Monday 21 September is the next Monday. */
    static final Instant BASE = Instant.parse("2026-09-19T04:00:00Z");
    static final String MONDAY = "2026-09-21";

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
        registry.add("qms.appointment.hold-expiry-check-cron", () -> "-");
        registry.add("qms.appointment.reminder-check-cron", () -> "-");
        registry.add("qms.queue.call-timeout-check-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-feature-flags");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired CapturingVisitorOtpMailer mailer;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private record World(UUID site, UUID zone, UUID group, UUID service, UUID team, UUID counter) {}

    private UUID newSite(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                id, "S-" + prefix + "-" + id.toString().substring(0, 8));
        return id;
    }

    /** A Site with one Zone, one Service group, one Service (reception, kiosk, mobile and both booking modes), a
     * Team, and a Counter serving that Service. */
    private World world(String prefix) {
        UUID site = newSite(prefix);
        UUID zone = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, floor_label) VALUES (?, ?, 'Hall', '1st')", zone, site);
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, ?)", group, site, "G" + prefix);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, ?, 10, 30, '[\"reception\",\"kiosk\",\"mobile\"]'::jsonb, 'both', true)",
                service, group, "T" + prefix);
        UUID team = UUID.randomUUID();
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, 'Desk')", team, group);
        UUID counter = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, '1')", counter, zone);
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", counter, service);
        return new World(site, zone, group, service, team, counter);
    }

    private UUID createUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                id, role + "-" + id, new BCryptPasswordEncoder(12).encode(PASSWORD), role, "en");
        return id;
    }

    private UUID newVisitor(String externalCode, String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO visitor (id, external_code, name, category, phone, created_at) VALUES (?, ?, 'Karim', 'general', ?, now())",
                id, externalCode, phone);
        return id;
    }

    /** Minted at real time whatever the test clock says: tokens are validated against the system clock, not this bean. */
    private String token(Role role, UUID site, UUID teamOf) throws Exception {
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            UUID user = createUser(role.wire());
            jdbc.update(
                    connection -> {
                        var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, user);
                        ps.setString(3, role.wire());
                        ps.setArray(4, connection.createArrayOf("uuid", site == null ? new UUID[0] : new UUID[] {site}));
                        ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
                        return ps;
                    });
            if (teamOf != null) {
                jdbc.update("INSERT INTO team_member (team_id, user_id) SELECT id, ? FROM team WHERE service_group_id = ?", user, teamOf);
            }
            String username = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, user);
            MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
            assertThat(status(result)).as(body(result)).isEqualTo(200);
            return field(result, "$.access_token");
        } finally {
            clock.set(testTime);
        }
    }

    /** Minted at real time whatever the test clock says, exactly like {@link #token}: an access token is validated
     * against the system clock, not the mocked one this test moves to {@link #BASE}. */
    private String visitorToken(String email) throws Exception {
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            return VisitorAuthTestSupport.mintAccessToken(mvc, mailer, email);
        } finally {
            clock.set(testTime);
        }
    }

    /** Minted at real time whatever the test clock says, exactly like {@link #token}. */
    private String pairDevice(String kind, String admin, UUID site, UUID zone) throws Exception {
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            String zoneField = zone == null ? "" : ",\"zone_id\":\"" + zone + "\"";
            MvcResult code = call(post("/api/v1/devices/pairing-codes"), admin, "{\"kind\":\"" + kind + "\",\"site_id\":\"" + site + "\"" + zoneField + ",\"label\":\"D\"}");
            assertThat(status(code)).as(body(code)).isEqualTo(201);
            MvcResult paired = call(post("/api/v1/devices/pair"), null, "{\"code\":\"" + field(code, "$.code") + "\"}");
            assertThat(status(paired)).as(body(paired)).isEqualTo(201);
            return field(paired, "$.access_token");
        } finally {
            clock.set(testTime);
        }
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
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

    private MvcResult putFlag(String admin, String key, boolean enabled) throws Exception {
        return call(put("/api/v1/setup/feature-flags/" + key), admin, "{\"enabled\":" + enabled + "}");
    }

    private void assertFeatureDisabled(MvcResult result, String feature) throws Exception {
        assertThat(status(result)).as(body(result)).isEqualTo(409);
        assertThat((String) field(result, "$.error.details.reason")).isEqualTo("feature_disabled");
        assertThat((String) field(result, "$.error.details.feature")).isEqualTo(feature);
    }

    /** The most recent {@code feature_flag.updated} audit entry's before/after for {@code key}: other tests in this
     * shared context may also have written this key, so only "most recent, read right after my own write" is
     * reliable, never an absolute position. */
    private String auditedValue(String key, String beforeOrAfter) {
        return jdbc.queryForObject(
                "SELECT (" + beforeOrAfter + "->>'enabled') FROM audit_log WHERE action = 'feature_flag.updated' AND after->>'key' = ? ORDER BY occurred_at DESC LIMIT 1",
                String.class,
                key);
    }

    // ---- appointment ---------------------------------------------------------------------------------------------

    @Test
    void theAppointmentFlagGatesBookingCheckInAndAvailabilitySearchAndIsAudited() throws Exception {
        World w = world("APT");
        String admin = token(Role.ORG_ADMIN, w.site(), null);
        jdbc.update(
                "INSERT INTO appointment_slot_template (id, level, target_id, weekday, start_time, end_time, slot_minutes, capacity)"
                        + " VALUES (?, 'service', ?, 1, '09:00', '10:00', 30, 2)",
                UUID.randomUUID(), w.service());
        // Another test in this shared context may have already touched this flag; start from a known "on" state
        // rather than relying on the no-row default, the same defensive reset JourneyIT's own flag test uses.
        assertThat(status(putFlag(admin, "appointment", true))).isEqualTo(200);

        MvcResult booked = call(post("/api/v1/appointments"), admin, bookJson(w.service()));
        assertThat(status(booked)).as(body(booked)).isEqualTo(201);
        UUID appointmentId = UUID.fromString(field(booked, "$.id"));

        // The mocked clock only moves when a helper explicitly bumps it; advance it so each write below gets its own
        // occurred_at and "most recent" ordering in the audit log is unambiguous.
        clock.set(clock.instant().plusSeconds(1));
        MvcResult off = putFlag(admin, "appointment", false);
        assertThat(status(off)).as(body(off)).isEqualTo(200);
        assertThat(auditedValue("appointment", "before")).isEqualTo("true");
        assertThat(auditedValue("appointment", "after")).isEqualTo("false");

        assertFeatureDisabled(call(post("/api/v1/appointments"), admin, bookJson(w.service())), "appointment");
        assertFeatureDisabled(call(get("/api/v1/services/" + w.service() + "/appointments/availability?date=" + MONDAY), admin, null), "appointment");
        assertFeatureDisabled(call(post("/api/v1/appointments/check-in"), admin, "{\"reference_code\":\"whatever\"}"), "appointment");

        // Turning the flag off never touches the appointment already booked.
        assertThat(jdbc.queryForObject("SELECT state FROM appointment WHERE id = ?", String.class, appointmentId)).isEqualTo("booked");

        clock.set(clock.instant().plusSeconds(1));
        MvcResult on = putFlag(admin, "appointment", true);
        assertThat(status(on)).as(body(on)).isEqualTo(200);
        assertThat(auditedValue("appointment", "before")).isEqualTo("false");
        assertThat(auditedValue("appointment", "after")).isEqualTo("true");

        MvcResult bookedAgain = call(post("/api/v1/appointments"), admin, bookJson(w.service()));
        assertThat(status(bookedAgain)).as(body(bookedAgain)).isEqualTo(201);
        MvcResult searchAgain = call(get("/api/v1/services/" + w.service() + "/appointments/availability?date=" + MONDAY), admin, null);
        assertThat(status(searchAgain)).as(body(searchAgain)).isEqualTo(200);
    }

    private String bookJson(UUID service) {
        return "{\"service_id\":\"" + service + "\",\"date\":\"" + MONDAY + "\",\"start\":\"09:00\",\"end\":\"09:30\",\"source\":\"phone\","
                + "\"contact_name\":\"Karim\",\"contact_phone\":\"01700000001\"}";
    }

    // ---- virtual_queue ---------------------------------------------------------------------------------------------

    @Test
    void theVirtualQueueFlagGatesRemoteJoinBeforeThePerServicePolicy() throws Exception {
        World w = world("VQ");
        String admin = token(Role.ORG_ADMIN, w.site(), null);
        MvcResult rule = call(put("/api/v1/services/" + w.service() + "/remote-rule"), admin, "{\"virtual_queue_enabled\":true}");
        assertThat(status(rule)).as(body(rule)).isEqualTo(200);
        String visitor = visitorToken("vq-" + UUID.randomUUID() + "@example.com");

        assertThat(status(putFlag(admin, "virtual_queue", false))).isEqualTo(200);
        // Refused with feature_disabled even though the Service's own policy is on (the master flag is checked first).
        assertFeatureDisabled(call(get("/api/v1/remote-join/" + w.service()), visitor, null), "virtual_queue");

        assertThat(status(putFlag(admin, "virtual_queue", true))).isEqualTo(200);
        MvcResult policy = call(get("/api/v1/remote-join/" + w.service()), visitor, null);
        assertThat(status(policy)).as(body(policy)).isEqualTo(200);
        assertThat((Boolean) field(policy, "$.virtual_queue_enabled")).isTrue();
    }

    // ---- journey ---------------------------------------------------------------------------------------------

    @Test
    void theJourneyFlagGatesIssuingAndTemplates() throws Exception {
        World w = world("JNY");
        UUID service2 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, active)"
                        + " VALUES (?, ?, '{\"en\":\"Billing\"}'::jsonb, 'B-JNY', 10, 30, '[\"reception\"]'::jsonb, true)",
                service2, w.group());
        String admin = token(Role.ORG_ADMIN, w.site(), null);
        String reception = token(Role.RECEPTION_OPERATOR, w.site(), w.group());
        assertThat(status(call(put("/api/v1/journey-settings"), admin, "{\"enabled\":true}"))).isEqualTo(200);

        assertThat(status(putFlag(admin, "journey", false))).isEqualTo(200);
        assertFeatureDisabled(
                call(post("/api/v1/journeys").header("Idempotency-Key", "j-" + UUID.randomUUID()), reception,
                        "{\"service_ids\":[\"" + w.service() + "\",\"" + service2 + "\"],\"ordered\":false}"),
                "journey");
        assertFeatureDisabled(call(get("/api/v1/sites/" + w.site() + "/journey-templates"), reception, null), "journey");

        assertThat(status(putFlag(admin, "journey", true))).isEqualTo(200);
        MvcResult issued = call(post("/api/v1/journeys").header("Idempotency-Key", "j-" + UUID.randomUUID()), reception,
                "{\"service_ids\":[\"" + w.service() + "\",\"" + service2 + "\"],\"ordered\":false}");
        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        MvcResult templates = call(get("/api/v1/sites/" + w.site() + "/journey-templates"), reception, null);
        assertThat(status(templates)).as(body(templates)).isEqualTo(200);
    }

    // ---- visitor_code_lookup ---------------------------------------------------------------------------------------------

    @Test
    void theVisitorCodeLookupFlagGatesLookupByCodeButNeverByPhone() throws Exception {
        World w = world("VCL");
        String admin = token(Role.ORG_ADMIN, w.site(), null);
        String code = "V-CODE" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        String phone = "01700000099";
        newVisitor(code, phone);
        String kiosk = pairDevice("kiosk", admin, w.site(), null);

        assertThat(status(putFlag(admin, "visitor_code_lookup", false))).isEqualTo(200);
        assertFeatureDisabled(call(get("/api/v1/visitors/lookup?q=" + code), admin, null), "visitor_code_lookup");
        assertFeatureDisabled(call(get("/api/v1/kiosk/visitors/identify?q=" + code), kiosk, null), "visitor_code_lookup");

        MvcResult byPhone = call(get("/api/v1/visitors/lookup?q=" + phone), admin, null);
        assertThat(status(byPhone)).as(body(byPhone)).isEqualTo(200);
        MvcResult identifyByPhone = call(get("/api/v1/kiosk/visitors/identify?q=" + phone), kiosk, null);
        assertThat(status(identifyByPhone)).as(body(identifyByPhone)).isEqualTo(200);

        assertThat(status(putFlag(admin, "visitor_code_lookup", true))).isEqualTo(200);
        MvcResult byCode = call(get("/api/v1/visitors/lookup?q=" + code), admin, null);
        assertThat(status(byCode)).as(body(byCode)).isEqualTo(200);
    }

    // ---- announce_visitor_name ---------------------------------------------------------------------------------------------

    @Test
    void theAnnounceVisitorNameFlagForcesTheDisplayPayloadFalse() throws Exception {
        World w = world("AVN");
        jdbc.update("UPDATE service SET announce_visitor_name = true WHERE id = ?", w.service());
        String admin = token(Role.ORG_ADMIN, w.site(), null);
        String reception = token(Role.RECEPTION_OPERATOR, w.site(), w.group());
        String agent = token(Role.AGENT, w.site(), w.group());
        String display = pairDevice("display", admin, w.site(), w.zone());

        MvcResult issued = call(post("/api/v1/tickets").header("Idempotency-Key", "t-" + UUID.randomUUID()), reception,
                "{\"service_id\":\"" + w.service() + "\",\"origin_channel\":\"reception\"}");
        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        MvcResult opened = call(post("/api/v1/sessions"), agent, "{\"counter_id\":\"" + w.counter() + "\"}");
        assertThat(status(opened)).as(body(opened)).isEqualTo(201);
        UUID sessionId = UUID.fromString(field(opened, "$.id"));
        MvcResult called = call(post("/api/v1/sessions/" + sessionId + "/next"), agent, null);
        assertThat(status(called)).as(body(called)).isEqualTo(200);

        assertThat(status(putFlag(admin, "announce_visitor_name", false))).isEqualTo(200);
        MvcResult stateOff = call(get("/api/v1/devices/" + deviceIdOf(display) + "/display-state"), display, null);
        assertThat(status(stateOff)).as(body(stateOff)).isEqualTo(200);
        assertThat((Boolean) field(stateOff, "$.serving[0].announce_visitor_name")).isFalse();

        assertThat(status(putFlag(admin, "announce_visitor_name", true))).isEqualTo(200);
        MvcResult stateOn = call(get("/api/v1/devices/" + deviceIdOf(display) + "/display-state"), display, null);
        assertThat(status(stateOn)).as(body(stateOn)).isEqualTo(200);
        assertThat((Boolean) field(stateOn, "$.serving[0].announce_visitor_name")).isTrue();
    }

    /** The device's own id, the same {@code sub} claim {@code /devices/{id}/display-state} checks against. */
    private UUID deviceIdOf(String token) {
        String[] parts = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        return UUID.fromString((String) JsonPath.read(payload, "$.sub"));
    }

    // ---- read access -----------------------------------------------------------------------------------------------

    @Test
    void readingFlagsNeedsNoConfigurationPermissionAndADeviceGetsThemThroughBootstrap() throws Exception {
        World w = world("RD");
        String admin = token(Role.ORG_ADMIN, w.site(), null);
        String reception = token(Role.RECEPTION_OPERATOR, w.site(), w.group());
        assertThat(status(putFlag(admin, "appointment", false))).isEqualTo(200);

        // Reading needs no config:org_sites_zones (the same reach GET /labels already has), unlike writing.
        MvcResult read = call(get("/api/v1/setup/feature-flags"), reception, null);
        assertThat(status(read)).as(body(read)).isEqualTo(200);
        assertThat((Boolean) field(read, "$.appointment")).isFalse();

        String kiosk = pairDevice("kiosk", admin, w.site(), null);
        MvcResult bootstrap = call(get("/api/v1/config/bootstrap"), kiosk, null);
        assertThat(status(bootstrap)).as(body(bootstrap)).isEqualTo(200);
        assertThat((Boolean) field(bootstrap, "$.feature_flags.appointment")).isFalse();
    }
}
