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
 * {@link AppointmentBookingService#sendDueReminders()} against real PostgreSQL (ticket 40, FR-APT-050): a reminder
 * fires once its slot is within a configured offset (default 24 h and 1 h) and never twice for the same
 * (appointment, offset) pair, however often the sweep runs (ADR-0010, {@code appointment_reminder_sent}'s unique
 * constraint). Kept to a single test in its own database: {@link AppointmentBookingService#sendDueReminders()}
 * sweeps every {@code booked} appointment with no per-Service or per-Site scope, so a second test method sharing
 * this class's Testcontainers Postgres would pick up the first one's own leftover booking.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, AppointmentReminderIT.Clocks.class})
class AppointmentReminderIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    /** Saturday 19 September 2026, 10:00 in Dhaka (UTC+6). Monday 21 September is the next Monday. */
    static final Instant BASE = Instant.parse("2026-09-19T04:00:00Z");
    static final String MONDAY = "2026-09-21";
    /** The 09:00 Dhaka slot on Monday, in UTC. */
    static final Instant SLOT_START = Instant.parse("2026-09-21T03:00:00Z");

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
        registry.add("qms.appointment.no-show-check-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-appointment-reminder");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired AppointmentBookingService bookingService;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures (same shape AppointmentNotificationIT/AppointmentRescheduleCancelIT use) -------------------------

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

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static <T> T field(MvcResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    private static UUID id(MvcResult result, String path) throws Exception {
        return UUID.fromString(field(result, path));
    }

    private MvcResult putServiceTemplate(String admin, UUID serviceId, int weekday, String start, String end, int slotMinutes, int capacity) throws Exception {
        String templateJson = String.format(
                "{\"items\":[{\"weekday\":%d,\"start\":\"%s\",\"end\":\"%s\",\"slot_minutes\":%d,\"capacity\":%d}]}", weekday, start, end, slotMinutes, capacity);
        return call(put("/api/v1/appointment-templates/service/" + serviceId), admin, templateJson);
    }

    private MvcResult book(String token, UUID serviceId, UUID visitorId, String date, String start, String end) throws Exception {
        return call(
                post("/api/v1/appointments"), token,
                String.format(
                        "{\"service_id\":\"%s\",\"date\":\"%s\",\"start\":\"%s\",\"end\":\"%s\",\"source\":\"staff\",\"visitor_id\":\"%s\"}", serviceId, date, start, end, visitorId));
    }

    private void template(String triggerKey, String channel, String language, String body) {
        jdbc.update(
                "INSERT INTO notification_template (id, trigger_key, channel, language, subject, body) VALUES (?, ?, ?, ?, 'Subject', ?)"
                        + " ON CONFLICT (trigger_key, channel, language) DO UPDATE SET body = EXCLUDED.body",
                UUID.randomUUID(), triggerKey, channel, language, body);
    }

    private List<Map<String, Object>> messagesFor(UUID visitorId, String triggerKey) {
        return jdbc.queryForList(
                "SELECT * FROM notification_message WHERE visitor_id = ? AND trigger_key = ? ORDER BY created_at", visitorId, triggerKey);
    }

    private int reminderSentCount(UUID appointmentId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM appointment_reminder_sent WHERE appointment_id = ?", Integer.class, appointmentId);
        return count == null ? 0 : count;
    }

    @Test
    void remindersFireAtEachConfiguredOffsetAndNeverTwiceForTheSameOne() throws Exception {
        template("appointment_reminder", "email", "en", "Reminder: {{token_number}} at {{date}} {{time}}");
        Setup s = setup("REMIND");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 1);
        UUID visitor = newVisitor("Karim", "01700000305");
        MvcResult booked = book(reception, s.service(), visitor, MONDAY, "09:00", "09:30");
        UUID appointmentId = id(booked, "$.id");

        // Two days before the slot: outside even the 24h offset window.
        clock.set(SLOT_START.minusSeconds(2 * 24 * 3600));
        assertThat(bookingService.sendDueReminders()).isZero();
        assertThat(messagesFor(visitor, "appointment_reminder")).isEmpty();

        // 20 hours before the slot: inside the 24h offset window, not yet the 1h one.
        clock.set(SLOT_START.minusSeconds(20 * 3600));
        assertThat(bookingService.sendDueReminders()).isEqualTo(1);
        assertThat(messagesFor(visitor, "appointment_reminder")).hasSize(1);
        assertThat(reminderSentCount(appointmentId)).isEqualTo(1);

        // A second sweep at the same moment sends nothing more (ADR-0010: claimed once per offset).
        assertThat(bookingService.sendDueReminders()).isZero();
        assertThat(messagesFor(visitor, "appointment_reminder")).hasSize(1);

        // 30 minutes before the slot: now inside the 1h offset window too; the 24h one stays claimed.
        clock.set(SLOT_START.minusSeconds(30 * 60));
        assertThat(bookingService.sendDueReminders()).isEqualTo(1);
        List<Map<String, Object>> messages = messagesFor(visitor, "appointment_reminder");
        assertThat(messages).hasSize(2);
        assertThat(reminderSentCount(appointmentId)).isEqualTo(2);
        assertThat((String) messages.get(1).get("rendered_body")).isEqualTo("Reminder: " + field(booked, "$.reference_code") + " at " + MONDAY + " 09:00");
    }
}
