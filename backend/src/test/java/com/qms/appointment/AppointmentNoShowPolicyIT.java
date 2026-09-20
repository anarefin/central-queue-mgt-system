package com.qms.appointment;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
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
 * Ticket 36, FR-APT-042, with {@code qms.appointment.no-show-policy-enabled=true} (default off; {@link
 * AppointmentNoShowIT} covers the default): 3 no-shows in the last 90 days (both defaults) block a visitor from
 * booking any way but walk-in. The policy is written as "every source but walk_in", not "phone or staff", so it
 * already covers the visitor self-service channel ticket 41 adds later (SRS FR-APT-042: "blocks online booking but
 * never blocks walk-in").
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, AppointmentNoShowPolicyIT.Clocks.class})
class AppointmentNoShowPolicyIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    /** Saturday 19 September 2026, 10:00 in Dhaka (UTC+6). */
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
        registry.add("qms.appointment.no-show-check-cron", () -> "-");
        registry.add("qms.appointment.no-show-policy-enabled", () -> "true");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-appointment-no-show-policy");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    private record Setup(UUID site, UUID group, UUID service) {}

    private UUID newSite() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                id, "S-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID newGroup(UUID site, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\",\"bn\":\"বহির্বিভাগ\"}'::jsonb, ?)", id, site, prefix);
        return id;
    }

    private UUID newService(UUID group, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\",\"bn\":\"পরামর্শ\"}'::jsonb, ?, 10, 30, '[\"reception\",\"kiosk\"]'::jsonb, 'both', true)",
                id, group, prefix);
        return id;
    }

    /** A site with one service group and one service, capacity high enough that FR-APT-011 never interferes with these tests. */
    private Setup setup(String prefix) {
        UUID site = newSite();
        UUID group = newGroup(site, "G" + prefix);
        UUID service = newService(group, prefix);
        return new Setup(site, group, service);
    }

    private UUID createUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                id, role + "-" + id, new BCryptPasswordEncoder(12).encode(PASSWORD), role, "en");
        return id;
    }

    private UUID newVisitor(String name, String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO visitor (id, external_code, name, category, phone, created_at) VALUES (?, ?, ?, 'general', ?, now())",
                id, "V-" + id.toString().substring(0, 8), name, phone);
        return id;
    }

    /** Minted at real time whatever the test clock says: tokens are validated against the system clock, not this bean. */
    private String token(Role role, UUID... sites) throws Exception {
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
                        ps.setArray(4, connection.createArrayOf("uuid", sites));
                        ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
                        return ps;
                    });
            String username = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, user);
            MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
            return JsonPath.read(result.getResponse().getContentAsString(), "$.access_token");
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

    private int count(String table, String where, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, args);
    }

    private MvcResult putServiceTemplate(String admin, UUID serviceId, int weekday, String start, String end, int slotMinutes, int capacity) throws Exception {
        String template = String.format(
                "{\"items\":[{\"weekday\":%d,\"start\":\"%s\",\"end\":\"%s\",\"slot_minutes\":%d,\"capacity\":%d}]}", weekday, start, end, slotMinutes, capacity);
        return call(put("/api/v1/appointment-templates/service/" + serviceId), admin, template);
    }

    private MvcResult book(String token, UUID serviceId, UUID visitorId, String source, String start, String end) throws Exception {
        return call(
                post("/api/v1/appointments"), token,
                String.format(
                        "{\"service_id\":\"%s\",\"date\":\"%s\",\"start\":\"%s\",\"end\":\"%s\",\"source\":\"%s\",\"visitor_id\":\"%s\"}",
                        serviceId, MONDAY, start, end, source, visitorId));
    }

    /** A visitor's no-show, marked (state's {@code updated_at}) {@code daysAgo} days before the test clock's current instant. */
    private void insertNoShowAppointment(UUID serviceId, UUID visitorId, int daysAgo) {
        UUID appointmentId = UUID.randomUUID();
        Instant markedAt = clock.instant().minus(daysAgo, ChronoUnit.DAYS);
        jdbc.update(
                "INSERT INTO appointment (id, reference_code, service_id, visitor_id, slot_date, slot_start, slot_end, state, source, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'no_show', 'staff', ?, ?)",
                appointmentId, "A-" + appointmentId.toString().substring(0, 8).toUpperCase(Locale.ROOT), serviceId, visitorId, LocalDate.parse(MONDAY).minusDays(daysAgo + 1),
                LocalTime.of(9, 0), LocalTime.of(9, 30), markedAt.minusSeconds(3600).atOffset(ZoneOffset.UTC), markedAt.atOffset(ZoneOffset.UTC));
    }

    // ---- FR-APT-042: blocked once the threshold is reached, but never for walk-in --------------------------------

    @Test
    void aVisitorWithThreeRecentNoShowsIsRefusedBookingByPhone() throws Exception {
        Setup s = setup("BLOCK");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "12:00", 30, 5);
        UUID visitor = newVisitor("Repeat", "01700000500");
        insertNoShowAppointment(s.service(), visitor, 10);
        insertNoShowAppointment(s.service(), visitor, 20);
        insertNoShowAppointment(s.service(), visitor, 30);

        MvcResult refused = book(reception, s.service(), visitor, "phone", "09:00", "09:30");

        assertThat(status(refused)).as(body(refused)).isEqualTo(409);
        assertThat((String) field(refused, "$.error.details.reason")).isEqualTo("no_show_policy");
        assertThat(count("appointment", "visitor_id = ? AND state = 'booked'", visitor)).isZero();
    }

    @Test
    void aVisitorWithThreeRecentNoShowsCanStillBeBookedAsAWalkIn() throws Exception {
        Setup s = setup("WALKIN");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "12:00", 30, 5);
        UUID visitor = newVisitor("Repeat", "01700000501");
        insertNoShowAppointment(s.service(), visitor, 10);
        insertNoShowAppointment(s.service(), visitor, 20);
        insertNoShowAppointment(s.service(), visitor, 30);

        MvcResult booked = book(reception, s.service(), visitor, "walk_in", "09:00", "09:30");

        assertThat(status(booked)).as(body(booked)).isEqualTo(201);
    }

    @Test
    void aVisitorWithOnlyTwoRecentNoShowsIsBelowTheDefaultThresholdOfThree() throws Exception {
        Setup s = setup("BELOW");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "12:00", 30, 5);
        UUID visitor = newVisitor("Twice", "01700000502");
        insertNoShowAppointment(s.service(), visitor, 10);
        insertNoShowAppointment(s.service(), visitor, 20);

        MvcResult booked = book(reception, s.service(), visitor, "staff", "09:00", "09:30");

        assertThat(status(booked)).as(body(booked)).isEqualTo(201);
    }

    @Test
    void aNoShowOutsideTheNinetyDayRollingWindowDoesNotCountTowardsTheThreshold() throws Exception {
        Setup s = setup("WINDOW");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "12:00", 30, 5);
        UUID visitor = newVisitor("Stale", "01700000503");
        insertNoShowAppointment(s.service(), visitor, 10);
        insertNoShowAppointment(s.service(), visitor, 20);
        insertNoShowAppointment(s.service(), visitor, 91); // just outside the default 90-day window

        MvcResult booked = book(reception, s.service(), visitor, "phone", "09:00", "09:30");

        assertThat(status(booked)).as(body(booked)).isEqualTo(201);
    }
}
