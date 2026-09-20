package com.qms.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * Ticket 40 against real PostgreSQL: confirmed, rescheduled/cancelled and waitlist-offer fire the §14.2 appointment
 * triggers with the appointment's own reference code standing in for {@code token_number} and its slot rendered as
 * {@code date}/{@code time} (FR-INT-040, FR-APT-050). {@link AppointmentReminderIT} covers {@link
 * AppointmentBookingService#sendDueReminders()} separately, in its own database, since a reminder sweep matches
 * every {@code booked} appointment and this class's own tests would otherwise interfere with each other's counts.
 * Delivery itself (the SMTP adapter and channel selection/fallback) is {@link com.qms.notification.EmailChannelIT}'s
 * and ticket 38's own coverage; this is only the wiring from the appointment lifecycle into the pipeline's own
 * queuing seam.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, AppointmentNotificationIT.Clocks.class})
class AppointmentNotificationIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    /** Saturday 19 September 2026, 10:00 in Dhaka (UTC+6). Monday 21 September is the next Monday. */
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
        registry.add("qms.appointment.no-show-check-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-appointment-notification");
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

    // ---- fixtures (same shape AppointmentRescheduleCancelIT uses) --------------------------------------------------

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

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
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

    private MvcResult enableWaitlist(String admin, UUID serviceId) throws Exception {
        return call(put("/api/v1/services/" + serviceId + "/appointment-settings"), admin, "{\"waitlist_enabled\":true}");
    }

    private MvcResult book(String token, UUID serviceId, UUID visitorId, String date, String start, String end) throws Exception {
        return call(
                post("/api/v1/appointments"), token,
                String.format(
                        "{\"service_id\":\"%s\",\"date\":\"%s\",\"start\":\"%s\",\"end\":\"%s\",\"source\":\"staff\",\"visitor_id\":\"%s\"}", serviceId, date, start, end, visitorId));
    }

    private MvcResult reschedule(String token, UUID appointmentId, String date, String start, String end) throws Exception {
        String json = "{\"date\":\"" + date + "\",\"start\":\"" + start + "\",\"end\":\"" + end + "\"}";
        return call(patch("/api/v1/appointments/" + appointmentId), token, json);
    }

    private MvcResult cancel(String token, UUID appointmentId) throws Exception {
        return call(delete("/api/v1/appointments/" + appointmentId), token, null);
    }

    // Templates are global (trigger x channel x language); upsert so several tests can each author their own.
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

    // ---- FR-APT confirmations, reschedule/cancel, waitlist offer ---------------------------------------------------

    @Test
    void bookingFiresAppointmentConfirmedWithTheReferenceCodeAndSlotAsVariables() throws Exception {
        template("appointment_confirmed", "email", "en", "Ref {{token_number}} on {{date}} at {{time}} for {{service_name}} at {{site_name}}");
        Setup s = setup("CONFIRM");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID visitor = newVisitor("Karim", "01700000301");

        MvcResult booked = book(reception, s.service(), visitor, MONDAY, "09:00", "09:30");

        assertThat(status(booked)).as(body(booked)).isEqualTo(201);
        String referenceCode = field(booked, "$.reference_code");
        List<Map<String, Object>> messages = messagesFor(visitor, "appointment_confirmed");
        assertThat(messages).hasSize(1);
        Map<String, Object> message = messages.get(0);
        // appointment_confirmed's default order is [email, web_push]; email is registered, so this starts on it.
        assertThat(message.get("channel")).isEqualTo("email");
        assertThat(message.get("status")).isEqualTo("queued");
        assertThat(message.get("language")).isEqualTo("en"); // visitor has no preference; falls back to the Site default (FR-NTF-022).
        assertThat((String) message.get("rendered_body")).isEqualTo("Ref " + referenceCode + " on " + MONDAY + " at 09:00 for Consultation at Main campus");
    }

    @Test
    void reschedulingFiresAppointmentRescheduledOrCancelledWithTheNewSlot() throws Exception {
        template("appointment_rescheduled_or_cancelled", "email", "en", "{{token_number}} moved to {{date}} {{time}}");
        Setup s = setup("RESCH");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID visitor = newVisitor("Karim", "01700000302");
        MvcResult booked = book(reception, s.service(), visitor, MONDAY, "09:00", "09:30");
        UUID appointmentId = id(booked, "$.id");
        String referenceCode = field(booked, "$.reference_code");

        MvcResult rescheduled = reschedule(reception, appointmentId, MONDAY, "10:00", "10:30");

        assertThat(status(rescheduled)).as(body(rescheduled)).isEqualTo(200);
        List<Map<String, Object>> messages = messagesFor(visitor, "appointment_rescheduled_or_cancelled");
        assertThat(messages).hasSize(1);
        assertThat((String) messages.get(0).get("rendered_body")).isEqualTo(referenceCode + " moved to " + MONDAY + " 10:00");
    }

    @Test
    void cancellingFiresRescheduledOrCancelledAndOffersTheFreedSlotToTheWaitlist() throws Exception {
        template("appointment_rescheduled_or_cancelled", "email", "en", "{{token_number}} cancelled");
        template("waitlist_slot_offered", "web_push", "en", "A slot opened up for {{token_number}}");
        Setup s = setup("CANCELNOTIF");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 1);
        enableWaitlist(admin, s.service());
        UUID first = newVisitor("First", "01700000303");
        UUID second = newVisitor("Second", "01700000304");
        MvcResult firstBooking = book(reception, s.service(), first, MONDAY, "09:00", "09:30");
        UUID firstId = id(firstBooking, "$.id");
        book(reception, s.service(), second, MONDAY, "09:00", "09:30"); // joins the waitlist: the slot is full.

        MvcResult cancelled = cancel(reception, firstId);

        assertThat(status(cancelled)).as(body(cancelled)).isEqualTo(204);
        assertThat(messagesFor(first, "appointment_rescheduled_or_cancelled")).hasSize(1);
        List<Map<String, Object>> offered = messagesFor(second, "waitlist_slot_offered");
        assertThat(offered).hasSize(1);
        // waitlist_slot_offered's default order is [web_push, email]; web_push is registered, so this starts on it.
        assertThat(offered.get(0).get("channel")).isEqualTo("web_push");
    }
}
