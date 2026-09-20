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
 * Ticket 34 against real PostgreSQL with a clock the test moves: {@code PATCH /appointments/{id}} reschedules up to
 * a configurable cut-off (default 2 h), staff any time with a reason, keeping the reference code and recording the
 * move in the audit log (FR-APT-020, FR-APT-021); {@code DELETE /appointments/{id}} cancels the same way and frees
 * capacity immediately (FR-APT-020, FR-APT-022); a Service's optional waitlist takes a full slot's overflow and
 * offers the first waiting visitor the seat a cancellation frees (FR-APT-023).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, AppointmentRescheduleCancelIT.Clocks.class})
class AppointmentRescheduleCancelIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    /** Saturday 19 September 2026, 10:00 in Dhaka (UTC+6). Monday 21 September is the next Monday. */
    static final Instant BASE = Instant.parse("2026-09-19T04:00:00Z");
    static final String MONDAY = "2026-09-21";
    /** 90 minutes before the 09:00 Dhaka slot on Monday — inside the default 2 h visitor cut-off. */
    static final Instant WITHIN_CUTOFF = Instant.parse("2026-09-21T01:30:00Z");

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
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-appointment-reschedule-cancel");
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

    // ---- fixtures (same shape AppointmentBookingIT/AppointmentAvailabilityIT use) --------------------------------

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

    private static UUID id(MvcResult result, String path) throws Exception {
        return UUID.fromString(field(result, path));
    }

    private int count(String table, String where, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, args);
    }

    private String stateOf(UUID appointmentId) {
        return jdbc.queryForObject("SELECT state FROM appointment WHERE id = ?", String.class, appointmentId);
    }

    private MvcResult putServiceTemplate(String admin, UUID serviceId, int weekday, String start, String end, int slotMinutes, int capacity) throws Exception {
        String template = String.format(
                "{\"items\":[{\"weekday\":%d,\"start\":\"%s\",\"end\":\"%s\",\"slot_minutes\":%d,\"capacity\":%d}]}", weekday, start, end, slotMinutes, capacity);
        return call(put("/api/v1/appointment-templates/service/" + serviceId), admin, template);
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

    private MvcResult reschedule(String token, UUID appointmentId, String date, String start, String end, String reason) throws Exception {
        StringBuilder json = new StringBuilder("{\"date\":\"" + date + "\",\"start\":\"" + start + "\",\"end\":\"" + end + "\"");
        if (reason != null) json.append(",\"reason\":\"").append(reason).append('"');
        json.append('}');
        return call(patch("/api/v1/appointments/" + appointmentId), token, json.toString());
    }

    private MvcResult cancel(String token, UUID appointmentId, String reason) throws Exception {
        String json = reason == null ? null : "{\"reason\":\"" + reason + "\"}";
        return call(delete("/api/v1/appointments/" + appointmentId), token, json);
    }

    // ---- FR-APT-020, FR-APT-021: reschedule ---------------------------------------------------------------------

    @Test
    void reschedulingWithinTheCutoffKeepsTheReferenceCodeAndIsRecordedInTheAuditLog() throws Exception {
        Setup s = setup("RESCH");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID visitor = newVisitor("Karim", "01700000101");
        MvcResult booked = book(reception, s.service(), visitor, MONDAY, "09:00", "09:30");
        assertThat(status(booked)).as(body(booked)).isEqualTo(201);
        UUID appointmentId = id(booked, "$.id");
        String referenceCode = field(booked, "$.reference_code");

        MvcResult rescheduled = reschedule(reception, appointmentId, MONDAY, "10:00", "10:30", null);

        assertThat(status(rescheduled)).as(body(rescheduled)).isEqualTo(200);
        assertThat((String) field(rescheduled, "$.reference_code")).isEqualTo(referenceCode);
        assertThat((String) field(rescheduled, "$.start")).isEqualTo("10:00");
        assertThat((String) field(rescheduled, "$.state")).isEqualTo("booked");
        assertThat(stateOf(appointmentId)).isEqualTo("booked");
        assertThat(count("appointment", "id = ? AND slot_start = '10:00:00'", appointmentId)).isEqualTo(1);
        assertThat(count("audit_log", "action = 'appointment.rescheduled' AND entity_id = ?", appointmentId)).isEqualTo(1);
    }

    @Test
    void reschedulingPastTheCutoffWithoutAReasonIsRefused() throws Exception {
        Setup s = setup("CUT1");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID visitor = newVisitor("Karim", "01700000102");
        MvcResult booked = book(reception, s.service(), visitor, MONDAY, "09:00", "09:30");
        UUID appointmentId = id(booked, "$.id");
        clock.set(WITHIN_CUTOFF);

        MvcResult refused = reschedule(reception, appointmentId, MONDAY, "10:00", "10:30", null);

        assertThat(status(refused)).as(body(refused)).isEqualTo(409);
        assertThat((String) field(refused, "$.error.details.reason")).isEqualTo("cutoff_passed");
        assertThat(stateOf(appointmentId)).isEqualTo("booked");
        assertThat(count("appointment", "id = ? AND slot_start = '09:00:00'", appointmentId)).isEqualTo(1);
    }

    @Test
    void staffMayRescheduleAnyTimeWithAReason() throws Exception {
        Setup s = setup("CUT2");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID visitor = newVisitor("Karim", "01700000103");
        MvcResult booked = book(reception, s.service(), visitor, MONDAY, "09:00", "09:30");
        UUID appointmentId = id(booked, "$.id");
        clock.set(WITHIN_CUTOFF);

        MvcResult rescheduled = reschedule(reception, appointmentId, MONDAY, "10:00", "10:30", "Visitor called in");

        assertThat(status(rescheduled)).as(body(rescheduled)).isEqualTo(200);
        assertThat(count("audit_log", "action = 'appointment.rescheduled' AND entity_id = ? AND reason = 'Visitor called in'", appointmentId)).isEqualTo(1);
    }

    @Test
    void reschedulingToAFullSlotIsRefusedAndTheOriginalBookingIsUnchanged() throws Exception {
        Setup s = setup("FULL1");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 1);
        UUID first = newVisitor("A", "01700000104");
        UUID second = newVisitor("B", "01700000105");
        MvcResult firstBooking = book(reception, s.service(), first, MONDAY, "09:00", "09:30");
        UUID firstId = id(firstBooking, "$.id");
        book(reception, s.service(), second, MONDAY, "09:30", "10:00");

        MvcResult refused = reschedule(reception, firstId, MONDAY, "09:30", "10:00", null);

        assertThat(status(refused)).as(body(refused)).isEqualTo(409);
        assertThat((String) field(refused, "$.error.details.reason")).isEqualTo("slot_full");
        assertThat(stateOf(firstId)).isEqualTo("booked");
        assertThat(count("appointment", "id = ? AND slot_start = '09:00:00'", firstId)).isEqualTo(1);
    }

    // ---- FR-APT-020, FR-APT-022: cancellation -------------------------------------------------------------------

    @Test
    void cancellingReturnsCapacityImmediately() throws Exception {
        Setup s = setup("CANCEL1");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 1);
        UUID first = newVisitor("A", "01700000106");
        UUID second = newVisitor("B", "01700000107");
        MvcResult firstBooking = book(reception, s.service(), first, MONDAY, "09:00", "09:30");
        UUID firstId = id(firstBooking, "$.id");
        MvcResult blocked = book(reception, s.service(), second, MONDAY, "09:00", "09:30");
        assertThat(status(blocked)).isEqualTo(409);

        MvcResult cancelled = cancel(reception, firstId, null);

        assertThat(status(cancelled)).as(body(cancelled)).isEqualTo(204);
        assertThat(stateOf(firstId)).isEqualTo("cancelled");
        assertThat(count("audit_log", "action = 'appointment.cancelled' AND entity_id = ?", firstId)).isEqualTo(1);
        MvcResult afterCancel = book(reception, s.service(), second, MONDAY, "09:00", "09:30");
        assertThat(status(afterCancel)).as(body(afterCancel)).isEqualTo(201);
    }

    @Test
    void cancellingPastTheCutoffWithoutAReasonIsRefused() throws Exception {
        Setup s = setup("CUT3");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 1);
        UUID visitor = newVisitor("A", "01700000108");
        MvcResult booked = book(reception, s.service(), visitor, MONDAY, "09:00", "09:30");
        UUID appointmentId = id(booked, "$.id");
        clock.set(WITHIN_CUTOFF);

        MvcResult refused = cancel(reception, appointmentId, null);

        assertThat(status(refused)).as(body(refused)).isEqualTo(409);
        assertThat((String) field(refused, "$.error.details.reason")).isEqualTo("cutoff_passed");
        assertThat(stateOf(appointmentId)).isEqualTo("booked");
    }

    @Test
    void staffMayCancelAnyTimeWithAReason() throws Exception {
        Setup s = setup("CUT4");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 1);
        UUID visitor = newVisitor("A", "01700000109");
        MvcResult booked = book(reception, s.service(), visitor, MONDAY, "09:00", "09:30");
        UUID appointmentId = id(booked, "$.id");
        clock.set(WITHIN_CUTOFF);

        MvcResult cancelled = cancel(reception, appointmentId, "Visitor no longer needs it");

        assertThat(status(cancelled)).as(body(cancelled)).isEqualTo(204);
        assertThat(count("audit_log", "action = 'appointment.cancelled' AND entity_id = ? AND reason = 'Visitor no longer needs it'", appointmentId)).isEqualTo(1);
    }

    @Test
    void cancellingAnAlreadyCancelledAppointmentIsRefused() throws Exception {
        Setup s = setup("CANCEL2");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 1);
        UUID visitor = newVisitor("A", "01700000110");
        MvcResult booked = book(reception, s.service(), visitor, MONDAY, "09:00", "09:30");
        UUID appointmentId = id(booked, "$.id");
        assertThat(status(cancel(reception, appointmentId, null))).isEqualTo(204);

        MvcResult refused = cancel(reception, appointmentId, null);

        assertThat(status(refused)).as(body(refused)).isEqualTo(409);
        assertThat((String) field(refused, "$.error.details.reason")).isEqualTo("not_booked");
    }

    @Test
    void rescheduleAndCancelOfAnUnknownAppointmentAreNotFound() throws Exception {
        Setup s = setup("NF");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());

        MvcResult patched = reschedule(reception, UUID.randomUUID(), MONDAY, "09:00", "09:30", null);
        MvcResult deleted = cancel(reception, UUID.randomUUID(), null);

        assertThat(status(patched)).isEqualTo(404);
        assertThat(status(deleted)).isEqualTo(404);
    }

    // ---- permission and scope ------------------------------------------------------------------------------------

    @Test
    void rescheduleAndCancelRequireThePermissionAndAreScopedToTheServicesSite() throws Exception {
        Setup s = setup("SCOPE2");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID visitor = newVisitor("A", "01700000111");
        MvcResult booked = book(reception, s.service(), visitor, MONDAY, "09:00", "09:30");
        UUID appointmentId = id(booked, "$.id");
        String agent = token(Role.AGENT, s.site());
        String otherSiteReception = token(Role.RECEPTION_OPERATOR, UUID.randomUUID());

        MvcResult deniedByPermission = reschedule(agent, appointmentId, MONDAY, "10:00", "10:30", null);
        MvcResult deniedByScope = cancel(otherSiteReception, appointmentId, null);

        assertThat(status(deniedByPermission)).isEqualTo(403);
        assertThat(status(deniedByScope)).isEqualTo(403);
        assertThat(stateOf(appointmentId)).isEqualTo("booked");
    }

    // ---- FR-APT-023: waitlist -------------------------------------------------------------------------------------

    @Test
    void aFullSlotWithWaitlistEnabledJoinsTheWaitlistInsteadOfBeingRefused() throws Exception {
        Setup s = setup("WL1");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 1);
        enableWaitlist(admin, s.service());
        UUID first = newVisitor("First", "01700000201");
        UUID second = newVisitor("Second", "01700000202");
        book(reception, s.service(), first, MONDAY, "09:00", "09:30");

        MvcResult joined = book(reception, s.service(), second, MONDAY, "09:00", "09:30");

        assertThat(status(joined)).as(body(joined)).isEqualTo(201);
        assertThat((String) field(joined, "$.state")).isEqualTo("waitlisted");
        assertThat((String) field(joined, "$.reference_code")).isNull();
        UUID waitlistId = id(joined, "$.id");
        assertThat(count("appointment_waitlist", "id = ? AND state = 'waiting' AND visitor_id = ?", waitlistId, second)).isEqualTo(1);
        assertThat(count("appointment", "service_id = ? AND state IN ('held_slot', 'booked')", s.service())).isEqualTo(1);
        assertThat(count("audit_log", "action = 'appointment.waitlisted' AND entity_id = ?", waitlistId)).isEqualTo(1);
    }

    @Test
    void aFullSlotWithNoWaitlistIsStillRefusedWithSlotFull() throws Exception {
        Setup s = setup("WL0");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 1);
        UUID first = newVisitor("First", "01700000203");
        UUID second = newVisitor("Second", "01700000204");
        book(reception, s.service(), first, MONDAY, "09:00", "09:30");

        MvcResult refused = book(reception, s.service(), second, MONDAY, "09:00", "09:30");

        assertThat(status(refused)).isEqualTo(409);
        assertThat((String) field(refused, "$.error.details.reason")).isEqualTo("slot_full");
    }

    @Test
    void cancellingOffersTheFreedSlotToTheFirstWaitlistedVisitor() throws Exception {
        Setup s = setup("WL2");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 1);
        enableWaitlist(admin, s.service());
        UUID first = newVisitor("First", "01700000205");
        UUID second = newVisitor("Second", "01700000206");
        MvcResult firstBooking = book(reception, s.service(), first, MONDAY, "09:00", "09:30");
        UUID firstId = id(firstBooking, "$.id");
        MvcResult joined = book(reception, s.service(), second, MONDAY, "09:00", "09:30");
        UUID waitlistId = id(joined, "$.id");

        MvcResult cancelled = cancel(reception, firstId, null);

        assertThat(status(cancelled)).as(body(cancelled)).isEqualTo(204);
        assertThat(count("appointment_waitlist", "id = ? AND state = 'offered'", waitlistId)).isEqualTo(1);
        UUID offeredAppointmentId = jdbc.queryForObject("SELECT offered_appointment_id FROM appointment_waitlist WHERE id = ?", UUID.class, waitlistId);
        assertThat(offeredAppointmentId).isNotNull();
        assertThat(count("appointment", "id = ? AND state = 'held_slot' AND visitor_id = ? AND hold_expires_at IS NOT NULL", offeredAppointmentId, second)).isEqualTo(1);
        assertThat(count("audit_log", "action = 'appointment.waitlist_offered' AND entity_id = ?", offeredAppointmentId)).isEqualTo(1);
    }
}
