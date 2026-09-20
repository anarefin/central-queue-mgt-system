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
 * Ticket 36 against real PostgreSQL, at the properties' real defaults: an appointment nobody ever tries to check in
 * for is marked {@code no_show} automatically once its slot plus grace period has passed (FR-APT-040), freeing its
 * capacity immediately (FR-APT-041) — the background sweep {@code AppointmentCheckInIT} already notes ticket 33's
 * reactive mark (a late check-in attempt) does not cover. FR-APT-042's repeat-no-show policy is disabled by default;
 * {@link AppointmentNoShowPolicyIT} covers it turned on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, AppointmentNoShowIT.Clocks.class})
class AppointmentNoShowIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    /** Saturday 19 September 2026, 10:00 in Dhaka (UTC+6). */
    static final Instant BASE = Instant.parse("2026-09-19T04:00:00Z");
    static final String MONDAY = "2026-09-21";
    /** The 09:00 Dhaka slot on Monday, in UTC. Default grace: 15 minutes, so the window ends at 03:15Z. */
    static final Instant SLOT_START = Instant.parse("2026-09-21T03:00:00Z");
    static final Instant WINDOW_END = SLOT_START.plusSeconds(15 * 60);

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
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-appointment-no-show");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired AppointmentBookingRepository repository;
    @Autowired AppointmentBookingService bookingService;

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

    /** A site with one service group and one service. */
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

    private String appointmentState(UUID id) {
        return jdbc.queryForObject("SELECT state FROM appointment WHERE id = ?", String.class, id);
    }

    private MvcResult putServiceTemplate(String admin, UUID serviceId, int weekday, String start, String end, int slotMinutes, int capacity) throws Exception {
        String template = String.format(
                "{\"items\":[{\"weekday\":%d,\"start\":\"%s\",\"end\":\"%s\",\"slot_minutes\":%d,\"capacity\":%d}]}", weekday, start, end, slotMinutes, capacity);
        return call(put("/api/v1/appointment-templates/service/" + serviceId), admin, template);
    }

    private MvcResult book(String token, UUID serviceId, UUID visitorId, String source) throws Exception {
        return call(
                post("/api/v1/appointments"), token,
                String.format(
                        "{\"service_id\":\"%s\",\"date\":\"%s\",\"start\":\"09:00\",\"end\":\"09:30\",\"source\":\"%s\",\"visitor_id\":\"%s\"}",
                        serviceId, MONDAY, source, visitorId));
    }

    private void insertNoShowAppointment(UUID serviceId, UUID visitorId, Instant markedAt) {
        UUID appointmentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO appointment (id, reference_code, service_id, visitor_id, slot_date, slot_start, slot_end, state, source, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'no_show', 'staff', ?, ?)",
                appointmentId, "A-" + appointmentId.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT), serviceId, visitorId, LocalDate.parse(MONDAY).minusDays(60),
                LocalTime.of(9, 0), LocalTime.of(9, 30), markedAt.minusSeconds(3600).atOffset(ZoneOffset.UTC), markedAt.atOffset(ZoneOffset.UTC));
    }

    // ---- FR-APT-040, FR-APT-041: automatic no-show and immediate capacity release -----------------------------

    @Test
    void aBookedAppointmentPastSlotPlusGraceIsAutomaticallyMarkedNoShowAndFreesItsCapacityImmediately() throws Exception {
        Setup s = setup("SWEEP");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 1);
        UUID visitor = newVisitor("Karim", "01700000400");
        MvcResult booked = book(reception, s.service(), visitor, "staff");
        assertThat(status(booked)).as(body(booked)).isEqualTo(201);
        UUID appointmentId = id(booked, "$.id");
        LocalDate slotDate = LocalDate.parse(MONDAY);

        clock.set(WINDOW_END.plusSeconds(1)); // one second past the grace period

        // Nobody ever tried to check in; the appointment is still `booked` and still counts against the slot's capacity.
        assertThat(repository.activeCountForSlot(s.service(), slotDate, LocalTime.of(9, 0), LocalTime.of(9, 30))).isEqualTo(1);

        int swept = bookingService.markOverdueNoShows();

        // At least this appointment; other tests in this shared-database class may also have left an overdue row at
        // the same absolute slot instant for the same sweep to pick up (each is asserted on its own id/audit entry).
        assertThat(swept).isGreaterThanOrEqualTo(1);
        assertThat(appointmentState(appointmentId)).isEqualTo("no_show");
        assertThat(count("audit_log", "action = 'appointment.no_show' AND entity_id = ?", appointmentId)).isEqualTo(1);

        // FR-APT-041: capacity is free the instant the sweep commits, the same way `activeCountForSlot` already
        // excludes `cancelled` for FR-APT-022's cancellation (Reschedule(IT)#cancellingReturnsCapacityImmediately).
        assertThat(repository.activeCountForSlot(s.service(), slotDate, LocalTime.of(9, 0), LocalTime.of(9, 30))).isZero();
    }

    @Test
    void anAppointmentExactlyAtTheEndOfItsGraceWindowIsNotSweptYet() throws Exception {
        Setup s = setup("EDGE");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 1);
        UUID visitor = newVisitor("Karim", "01700000402");
        MvcResult booked = book(reception, s.service(), visitor, "staff");
        UUID appointmentId = id(booked, "$.id");

        clock.set(WINDOW_END); // exactly the window's inclusive end: still on time, the same edge CheckInIT covers

        int swept = bookingService.markOverdueNoShows();

        assertThat(swept).isZero();
        assertThat(appointmentState(appointmentId)).isEqualTo("booked");
    }

    // ---- FR-APT-042: disabled by default -------------------------------------------------------------------------

    @Test
    void withThePolicyDisabledByDefaultRepeatNoShowsNeverBlockBooking() throws Exception {
        Setup s = setup("OFF");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 5);
        UUID visitor = newVisitor("Repeat", "01700000403");
        Instant recently = clock.instant().minusSeconds(3600);
        insertNoShowAppointment(s.service(), visitor, recently);
        insertNoShowAppointment(s.service(), visitor, recently);
        insertNoShowAppointment(s.service(), visitor, recently);

        MvcResult attempt = book(reception, s.service(), visitor, "phone");

        assertThat(status(attempt)).as(body(attempt)).isEqualTo(201);
    }
}
