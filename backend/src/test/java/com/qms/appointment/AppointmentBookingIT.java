package com.qms.appointment;

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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Ticket 33 against real PostgreSQL with a clock the test moves: {@code POST /appointments} books transactionally
 * against remaining capacity (FR-APT-011), records the booking source (FR-APT-013), gives a unique reference code
 * (FR-APT-014), captures an existing visitor or a minimal contact record plus service, slot, preferred Agent, purpose
 * and language (FR-APT-015), and enforces a visitor's active-appointment cap (FR-APT-016). {@link
 * AppointmentBookingService#releaseExpiredHolds()} covers FR-APT-012's hold-then-release job directly, the same way a
 * held row would ever exist in the database once a caller (ticket 41) leaves one incomplete.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, AppointmentBookingIT.Clocks.class})
class AppointmentBookingIT {

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
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-appointment-booking");
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

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private record Setup(UUID site, UUID group, UUID service, UUID team, UUID agent) {}

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

    private UUID newTeam(UUID group) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, 'Desk')", id, group);
        return id;
    }

    private UUID newAgent(UUID team) {
        UUID user = createUser("agent");
        jdbc.update("INSERT INTO team_member (team_id, user_id) VALUES (?, ?)", team, user);
        return user;
    }

    /** A site with one service group, one service, its team and one rostered agent. */
    private Setup setup(String prefix) {
        UUID site = newSite();
        UUID group = newGroup(site, "G" + prefix);
        UUID service = newService(group, prefix);
        UUID team = newTeam(group);
        UUID agent = newAgent(team);
        return new Setup(site, group, service, team, agent);
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

    private MvcResult putServiceTemplate(String admin, UUID serviceId, int weekday, String start, String end, int slotMinutes, int capacity) throws Exception {
        String template = String.format(
                "{\"items\":[{\"weekday\":%d,\"start\":\"%s\",\"end\":\"%s\",\"slot_minutes\":%d,\"capacity\":%d}]}", weekday, start, end, slotMinutes, capacity);
        return call(put("/api/v1/appointment-templates/service/" + serviceId), admin, template);
    }

    private MvcResult search(String token, UUID serviceId, String date) throws Exception {
        return call(get("/api/v1/services/" + serviceId + "/appointments/availability?date=" + date), token, null);
    }

    private MvcResult book(String token, String requestBody) throws Exception {
        return call(post("/api/v1/appointments"), token, requestBody);
    }

    private String bookExistingVisitor(UUID serviceId, UUID visitorId, String source) {
        return String.format(
                "{\"service_id\":\"%s\",\"date\":\"%s\",\"start\":\"09:00\",\"end\":\"09:30\",\"source\":\"%s\",\"visitor_id\":\"%s\"}",
                serviceId, MONDAY, source, visitorId);
    }

    // ---- FR-APT-014, FR-APT-015: reference code and captured fields ---------------------------------------------

    @Test
    void aStaffBookingIsConfirmedWithAUniqueReferenceCodeAndTheCapturedFields() throws Exception {
        Setup s = setup("BK");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "10:00", 30, 2);
        UUID visitor = newVisitor("Karim", "01700000001");

        MvcResult booked = book(
                reception,
                String.format(
                        "{\"service_id\":\"%s\",\"date\":\"%s\",\"start\":\"09:00\",\"end\":\"09:30\",\"source\":\"phone\",\"visitor_id\":\"%s\","
                                + "\"preferred_agent_id\":\"%s\",\"purpose_note\":\"Renewal\",\"language\":\"bn\"}",
                        s.service(), MONDAY, visitor, s.agent()));

        assertThat(status(booked)).as(body(booked)).isEqualTo(201);
        assertThat((String) field(booked, "$.state")).isEqualTo("booked");
        assertThat((String) field(booked, "$.source")).isEqualTo("phone");
        assertThat((String) field(booked, "$.visitor_id")).isEqualTo(visitor.toString());
        assertThat((String) field(booked, "$.preferred_agent_id")).isEqualTo(s.agent().toString());
        assertThat((String) field(booked, "$.purpose_note")).isEqualTo("Renewal");
        assertThat((String) field(booked, "$.language")).isEqualTo("bn");
        assertThat((String) field(booked, "$.service_id")).isEqualTo(s.service().toString());
        assertThat((String) field(booked, "$.date")).isEqualTo(MONDAY);
        assertThat((String) field(booked, "$.start")).isEqualTo("09:00");
        assertThat((String) field(booked, "$.end")).isEqualTo("09:30");
        String reference = field(booked, "$.reference_code");
        assertThat(reference).matches("^A-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8}$");
        assertThat(count("appointment", "id = ?", id(booked, "$.id"))).isEqualTo(1);
        assertThat(count("audit_log", "action = 'appointment.booked' AND entity_id = ?", id(booked, "$.id"))).isEqualTo(1);
    }

    @Test
    void twoBookingsForTheSameServiceGetDifferentReferenceCodes() throws Exception {
        Setup s = setup("RC");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "10:00", 30, 5);
        UUID v1 = newVisitor("A", "01700000010");
        UUID v2 = newVisitor("B", "01700000011");

        MvcResult first = book(reception, bookExistingVisitor(s.service(), v1, "staff"));
        MvcResult second = book(reception, bookExistingVisitor(s.service(), v2, "staff"));

        assertThat((String) field(first, "$.reference_code")).isNotEqualTo((String) field(second, "$.reference_code"));
    }

    @Test
    void aWalkInBookingWithNoExistingVisitorCreatesAMinimalContactRecord() throws Exception {
        Setup s = setup("WI");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "10:00", 30, 2);

        MvcResult booked = book(
                reception,
                String.format(
                        "{\"service_id\":\"%s\",\"date\":\"%s\",\"start\":\"09:00\",\"end\":\"09:30\",\"source\":\"walk_in\",\"contact_name\":\"Rahim\",\"contact_phone\":\"01711111111\"}",
                        s.service(), MONDAY));

        assertThat(status(booked)).as(body(booked)).isEqualTo(201);
        assertThat((String) field(booked, "$.source")).isEqualTo("walk_in");
        UUID visitorId = id(booked, "$.visitor_id");
        assertThat(count("visitor", "id = ? AND name = 'Rahim' AND phone = '01711111111'", visitorId)).isEqualTo(1);
    }

    // ---- FR-APT-011: transactional booking against remaining capacity ---------------------------------------------

    @Test
    void bookingConsumesCapacityAndTheSearchShowsTheReducedRemainingCapacity() throws Exception {
        Setup s = setup("CAP");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "10:00", 30, 2);
        UUID visitor = newVisitor("Karim", "01700000002");

        book(reception, bookExistingVisitor(s.service(), visitor, "staff"));
        MvcResult found = search(reception, s.service(), MONDAY);

        assertThat((List<String>) field(found, "$.slots[*].start")).containsExactly("09:00", "09:30");
        assertThat((List<Integer>) field(found, "$.slots[*].remaining_capacity")).containsExactly(1, 2);
    }

    @Test
    void bookingBeyondRemainingCapacityIsRefusedWithSlotFull() throws Exception {
        Setup s = setup("FULL");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 1);
        UUID first = newVisitor("A", "01700000020");
        UUID second = newVisitor("B", "01700000021");

        MvcResult firstBooking = book(reception, bookExistingVisitor(s.service(), first, "staff"));
        assertThat(status(firstBooking)).as(body(firstBooking)).isEqualTo(201);

        MvcResult secondBooking = book(reception, bookExistingVisitor(s.service(), second, "staff"));

        assertThat(status(secondBooking)).as(body(secondBooking)).isEqualTo(409);
        assertThat((String) field(secondBooking, "$.error.details.reason")).isEqualTo("slot_full");
        assertThat(count("appointment", "service_id = ? AND state = 'booked'", s.service())).isEqualTo(1);
    }

    @Test
    void twoSimultaneousBookingsForTheLastSeatResultInExactlyOneSuccess() throws Exception {
        Setup s = setup("RACE");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 1);
        UUID first = newVisitor("A", "01700000030");
        UUID second = newVisitor("B", "01700000031");

        List<Callable<MvcResult>> tasks = List.of(
                () -> book(reception, bookExistingVisitor(s.service(), first, "staff")), () -> book(reception, bookExistingVisitor(s.service(), second, "staff")));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> results;
        try {
            List<Future<MvcResult>> done = pool.invokeAll(tasks);
            results = List.of(status(done.get(0).get()), status(done.get(1).get()));
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).as("exactly one of the two racing bookings succeeds").containsExactlyInAnyOrder(201, 409);
        assertThat(count("appointment", "service_id = ? AND state = 'booked'", s.service())).isEqualTo(1);
    }

    // ---- FR-APT-013: booking source ---------------------------------------------------------------------------

    @Test
    void theBookingSourceIsRecordedAsGiven() throws Exception {
        Setup s = setup("SRC");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "10:00", 30, 3);
        UUID visitor = newVisitor("A", "01700000040");

        MvcResult phone = book(reception, bookExistingVisitor(s.service(), visitor, "phone"));

        assertThat((String) field(phone, "$.source")).isEqualTo("phone");
        assertThat(count("appointment", "id = ? AND source = 'phone'", id(phone, "$.id"))).isEqualTo(1);
    }

    @Test
    void anUnknownSourceIsRejected() throws Exception {
        Setup s = setup("BADSRC");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "10:00", 30, 2);
        UUID visitor = newVisitor("A", "01700000041");

        MvcResult rejected = book(reception, bookExistingVisitor(s.service(), visitor, "reception"));

        assertThat(status(rejected)).as(body(rejected)).isEqualTo(400);
        assertThat((String) field(rejected, "$.error.code")).isEqualTo("validation_failed");
    }

    // ---- FR-APT-016: max active appointments per visitor -----------------------------------------------------

    @Test
    void aVisitorMayNotExceedTheDefaultOfThreeActiveAppointments() throws Exception {
        Setup s = setup("MAX");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 5);
        UUID visitor = newVisitor("Frequent", "01700000050");
        String[] slots = {"09:00", "09:30", "10:00", "10:30"};

        for (int i = 0; i < 3; i++) {
            MvcResult ok = book(
                    reception,
                    String.format(
                            "{\"service_id\":\"%s\",\"date\":\"%s\",\"start\":\"%s\",\"end\":\"%s\",\"source\":\"staff\",\"visitor_id\":\"%s\"}",
                            s.service(), MONDAY, slots[i], plusMinutes(slots[i]), visitor));
            assertThat(status(ok)).as(body(ok)).isEqualTo(201);
        }

        MvcResult fourth = book(
                reception,
                String.format(
                        "{\"service_id\":\"%s\",\"date\":\"%s\",\"start\":\"%s\",\"end\":\"%s\",\"source\":\"staff\",\"visitor_id\":\"%s\"}",
                        s.service(), MONDAY, slots[3], plusMinutes(slots[3]), visitor));

        assertThat(status(fourth)).as(body(fourth)).isEqualTo(409);
        assertThat((String) field(fourth, "$.error.details.reason")).isEqualTo("max_active_appointments");
        assertThat(count("appointment", "visitor_id = ?", visitor)).isEqualTo(3);
    }

    private static String plusMinutes(String hhmm) {
        int h = Integer.parseInt(hhmm.substring(0, 2));
        int m = Integer.parseInt(hhmm.substring(3, 5)) + 30;
        if (m >= 60) {
            m -= 60;
            h += 1;
        }
        return String.format("%02d:%02d", h, m);
    }

    // ---- permission and scope ----------------------------------------------------------------------------------

    @Test
    void bookingRequiresThePermissionAndIsScopedToTheServicesSite() throws Exception {
        Setup s = setup("SCOPE");
        String admin = token(Role.ORG_ADMIN, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "10:00", 30, 2);
        UUID visitor = newVisitor("A", "01700000060");
        String agentToken = token(Role.AGENT, s.site());
        String otherSiteReception = token(Role.RECEPTION_OPERATOR, UUID.randomUUID());

        MvcResult deniedByPermission = book(agentToken, bookExistingVisitor(s.service(), visitor, "staff"));
        MvcResult deniedByScope = book(otherSiteReception, bookExistingVisitor(s.service(), visitor, "staff"));

        assertThat(status(deniedByPermission)).isEqualTo(403);
        assertThat(status(deniedByScope)).isEqualTo(403);
        assertThat(count("appointment", "service_id = ?", s.service())).isEqualTo(0);
    }

    // ---- FR-APT-012: hold released by the job ----------------------------------------------------------------

    @Test
    void aHeldSlotPastItsHoldIsReleasedByTheJobFreeingItsCapacity() throws Exception {
        Setup s = setup("HOLD");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 1);
        UUID heldVisitor = newVisitor("Held", "01700000070");
        UUID nextVisitor = newVisitor("Next", "01700000071");

        UUID heldId = UUID.randomUUID();
        Instant now = clock.instant();
        repository.insertHeld(
                heldId, "A-TESTHOLD", s.service(), null, heldVisitor, LocalDate.parse(MONDAY), LocalTime.of(9, 0), LocalTime.of(9, 30), "staff", null, null, null,
                now.minusSeconds(1), now.minusSeconds(60));

        // Fully consumed by the still-held row: a new booking for the same slot is refused.
        MvcResult beforeExpiry = book(reception, bookExistingVisitor(s.service(), nextVisitor, "staff"));
        assertThat(status(beforeExpiry)).as(body(beforeExpiry)).isEqualTo(409);

        int released = bookingService.releaseExpiredHolds();

        assertThat(released).isEqualTo(1);
        assertThat(count("appointment", "id = ?", heldId)).isEqualTo(0);
        assertThat(count("audit_log", "action = 'appointment.hold_expired' AND entity_id = ?", heldId)).isEqualTo(1);

        MvcResult afterExpiry = book(reception, bookExistingVisitor(s.service(), nextVisitor, "staff"));
        assertThat(status(afterExpiry)).as(body(afterExpiry)).isEqualTo(201);
    }
}
