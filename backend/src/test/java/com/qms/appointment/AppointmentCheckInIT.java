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
 * Ticket 35 against real PostgreSQL with a clock the test moves: a booked appointment checks in at reception or the
 * kiosk by its reference code, within a configurable window around the slot (default 30 minutes before to 15 minutes
 * after, FR-ISS-031); on time it converts to a Ticket carrying the appointment's Priority class and ordered from the
 * later of the slot time and the check-in, plus a bonus (FR-APT-030, FR-QUE-020, FR-APT-032); early it offers a
 * walk-in Ticket instead, leaving the appointment booked (FR-ISS-032); late past the grace period it follows the
 * no-show policy (FR-ISS-033, §9.5) instead of converting; a checked-in appointment never displaces a Ticket already
 * being served (FR-APT-031).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, AppointmentCheckInIT.Clocks.class})
class AppointmentCheckInIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    /** Saturday 19 September 2026, 10:00 in Dhaka (UTC+6). */
    static final Instant BASE = Instant.parse("2026-09-19T04:00:00Z");
    static final String MONDAY = "2026-09-21";
    /** The 09:00 Dhaka slot on Monday, in UTC. Default window: 30 minutes before to 15 minutes after. */
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
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-appointment-checkin");
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

    private record Setup(UUID site, UUID zone, UUID group, UUID service, UUID team, UUID counter) {}

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

    private UUID newCounter(UUID zone, UUID service) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, '1')", id, zone);
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", id, service);
        return id;
    }

    /** A site with one service group, one service (reception + kiosk channels), its team, a counter and a zone. */
    private Setup setup(String prefix) {
        UUID site = newSite();
        UUID zone = newZone(site);
        UUID group = newGroup(site, "G" + prefix);
        UUID service = newService(group, prefix);
        UUID team = newTeam(group);
        UUID counter = newCounter(zone, service);
        return new Setup(site, zone, group, service, team, counter);
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

    private UUID newAgentOnTeam(UUID team) {
        UUID user = createUser("agent");
        jdbc.update("INSERT INTO team_member (team_id, user_id) VALUES (?, ?)", team, user);
        return user;
    }

    /** Minted at real time whatever the test clock says: tokens are validated against the system clock, not this bean. */
    private String token(Role role, UUID... sites) throws Exception {
        return tokenFor(role, createUser(role.wire()), sites);
    }

    private String tokenFor(Role role, UUID userId, UUID... sites) throws Exception {
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            jdbc.update(
                    connection -> {
                        var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, userId);
                        ps.setString(3, role.wire());
                        ps.setArray(4, connection.createArrayOf("uuid", sites));
                        ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
                        return ps;
                    });
            String username = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, userId);
            MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
            return JsonPath.read(result.getResponse().getContentAsString(), "$.access_token");
        } finally {
            clock.set(testTime);
        }
    }

    private String pairingCode(String admin, UUID site) throws Exception {
        MvcResult created = call(post("/api/v1/devices/pairing-codes"), admin, "{\"kind\":\"kiosk\",\"site_id\":\"" + site + "\",\"label\":\"Lobby kiosk\"}");
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        return field(created, "$.code");
    }

    /** Pairs a fresh kiosk device for {@code site} and returns its access token. Minted at real time, like a staff token. */
    private String kioskToken(UUID site) throws Exception {
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            String admin = token(Role.ORG_ADMIN);
            MvcResult paired = call(post("/api/v1/devices/pair"), null, "{\"code\":\"" + pairingCode(admin, site) + "\"}");
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

    private MvcResult book(String token, UUID serviceId, UUID visitorId, UUID priorityClassId) throws Exception {
        String priority = priorityClassId == null ? "" : ",\"priority_class_id\":\"" + priorityClassId + "\"";
        return call(
                post("/api/v1/appointments"), token,
                String.format(
                        "{\"service_id\":\"%s\",\"date\":\"%s\",\"start\":\"09:00\",\"end\":\"09:30\",\"source\":\"staff\",\"visitor_id\":\"%s\"%s}",
                        serviceId, MONDAY, visitorId, priority));
    }

    /** Books an appointment for the 09:00 Monday slot, confirming it landed, and returns its reference code. */
    private String bookedReferenceCode(String reception, UUID serviceId, UUID visitorId) throws Exception {
        MvcResult booked = book(reception, serviceId, visitorId, null);
        assertThat(status(booked)).as(body(booked)).isEqualTo(201);
        return field(booked, "$.reference_code");
    }

    private MvcResult checkInReception(String token, String referenceCode) throws Exception {
        return call(post("/api/v1/appointments/check-in"), token, "{\"reference_code\":\"" + referenceCode + "\"}");
    }

    private MvcResult checkInKiosk(String token, String referenceCode) throws Exception {
        return call(post("/api/v1/kiosk/appointments/check-in"), token, "{\"reference_code\":\"" + referenceCode + "\"}");
    }

    private String appointmentState(UUID id) {
        return jdbc.queryForObject("SELECT state FROM appointment WHERE id = ?", String.class, id);
    }

    private MvcResult dryRun(String token, UUID service) throws Exception {
        return call(get("/api/v1/queues/" + service + "/dry-run"), token, null);
    }

    private UUID newPriorityClass(String admin, String name, int headstart) throws Exception {
        MvcResult created = call(post("/api/v1/priority-classes"), admin, "{\"name_i18n\":{\"en\":\"" + name + "\"},\"headstart_minutes\":" + headstart + "}");
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        return id(created, "$.id");
    }

    // ---- FR-ISS-030: check-in by reference code, at reception and at the kiosk -------------------------------------

    @Test
    void checkInAtReceptionWithinTheWindowConvertsTheAppointmentToATicket() throws Exception {
        Setup s = setup("RX");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID visitor = newVisitor("Karim", "01700000301");
        MvcResult booked = book(reception, s.service(), visitor, null);
        UUID appointmentId = id(booked, "$.id");
        String reference = field(booked, "$.reference_code");
        clock.set(SLOT_START); // exactly on time

        MvcResult checkedIn = checkInReception(reception, reference);

        assertThat(status(checkedIn)).as(body(checkedIn)).isEqualTo(200);
        assertThat((String) field(checkedIn, "$.outcome")).isEqualTo("converted");
        assertThat((String) field(checkedIn, "$.appointment_id")).isEqualTo(appointmentId.toString());
        assertThat((String) field(checkedIn, "$.ticket.origin_channel")).isEqualTo("appointment_checkin");
        assertThat((String) field(checkedIn, "$.ticket.state")).isEqualTo("waiting");
        assertThat(appointmentState(appointmentId)).isEqualTo("converted");
        UUID ticketId = id(checkedIn, "$.ticket.id");
        assertThat(count("appointment", "id = ? AND ticket_id = ? AND checked_in_at IS NOT NULL", appointmentId, ticketId)).isEqualTo(1);
        assertThat(count("audit_log", "action = 'appointment.checked_in' AND entity_id = ?", appointmentId)).isEqualTo(1);
    }

    @Test
    void checkInAtTheKioskWithinTheWindowAlsoConvertsTheAppointment() throws Exception {
        Setup s = setup("KX");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID visitor = newVisitor("Karim", "01700000302");
        String reference = bookedReferenceCode(reception, s.service(), visitor);
        String kiosk = kioskToken(s.site());
        clock.set(SLOT_START);

        MvcResult checkedIn = checkInKiosk(kiosk, reference);

        assertThat(status(checkedIn)).as(body(checkedIn)).isEqualTo(200);
        assertThat((String) field(checkedIn, "$.outcome")).isEqualTo("converted");
        assertThat((String) field(checkedIn, "$.ticket.origin_channel")).isEqualTo("appointment_checkin");
    }

    @Test
    void aStaffTokenIsRefusedAtTheKioskCheckInEndpoint() throws Exception {
        Setup s = setup("KROLE");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID visitor = newVisitor("Karim", "01700000303");
        String reference = bookedReferenceCode(reception, s.service(), visitor);

        assertThat(status(checkInKiosk(reception, reference))).isEqualTo(403);
    }

    // ---- FR-ISS-031: the window, and its bounds ----------------------------------------------------------------

    @Test
    void checkInIsAcceptedAtBothEdgesOfTheDefaultWindow() throws Exception {
        Setup s = setup("EDGE");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 5);
        UUID early = newVisitor("Early", "01700000310");
        UUID late = newVisitor("Late", "01700000311");
        String earlyRef = bookedReferenceCode(reception, s.service(), early);
        String lateRef = bookedReferenceCode(reception, s.service(), late);

        clock.set(SLOT_START.minusSeconds(30 * 60)); // exactly 30 minutes early: the window's inclusive start
        MvcResult atStart = checkInReception(reception, earlyRef);
        assertThat(status(atStart)).as(body(atStart)).isEqualTo(200);
        assertThat((String) field(atStart, "$.outcome")).isEqualTo("converted");

        clock.set(SLOT_START.plusSeconds(15 * 60)); // exactly 15 minutes late: the window's inclusive end
        MvcResult atEnd = checkInReception(reception, lateRef);
        assertThat(status(atEnd)).as(body(atEnd)).isEqualTo(200);
        assertThat((String) field(atEnd, "$.outcome")).isEqualTo("converted");
    }

    // ---- FR-ISS-032: early check-in offers a walk-in ticket, without cancelling the appointment ------------------

    @Test
    void earlyCheckInBeforeTheWindowOffersAWalkInTicketWithoutCancellingTheAppointment() throws Exception {
        Setup s = setup("EARLY");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID visitor = newVisitor("Karim", "01700000320");
        MvcResult booked = book(reception, s.service(), visitor, null);
        UUID appointmentId = id(booked, "$.id");
        String reference = field(booked, "$.reference_code");
        clock.set(SLOT_START.minusSeconds(40 * 60)); // 40 minutes early: outside the default 30-minute lead

        MvcResult checkedIn = checkInReception(reception, reference);

        assertThat(status(checkedIn)).as(body(checkedIn)).isEqualTo(200);
        assertThat((String) field(checkedIn, "$.outcome")).isEqualTo("walk_in");
        assertThat((String) field(checkedIn, "$.ticket.origin_channel")).isEqualTo("reception");
        assertThat(appointmentState(appointmentId)).as("the appointment is not cancelled").isEqualTo("booked");
        assertThat(count("audit_log", "action = 'appointment.checkin_early_walk_in' AND entity_id = ?", appointmentId)).isEqualTo(1);
    }

    // ---- FR-ISS-033, §9.5: late beyond the grace period follows the no-show policy --------------------------------

    @Test
    void lateCheckInPastTheGracePeriodIsRefusedAndTheAppointmentIsMarkedNoShow() throws Exception {
        Setup s = setup("LATE");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID visitor = newVisitor("Karim", "01700000330");
        MvcResult booked = book(reception, s.service(), visitor, null);
        UUID appointmentId = id(booked, "$.id");
        String reference = field(booked, "$.reference_code");
        clock.set(SLOT_START.plusSeconds(20 * 60)); // 20 minutes late: past the default 15-minute grace

        MvcResult refused = checkInReception(reception, reference);

        assertThat(status(refused)).as(body(refused)).isEqualTo(409);
        assertThat((String) field(refused, "$.error.details.reason")).isEqualTo("no_show");
        assertThat(appointmentState(appointmentId)).isEqualTo("no_show");
        assertThat(count("audit_log", "action = 'appointment.no_show' AND entity_id = ?", appointmentId)).isEqualTo(1);

        // Retrying does not convert a no-show appointment.
        MvcResult retried = checkInReception(reception, reference);
        assertThat(status(retried)).isEqualTo(409);
        assertThat((String) field(retried, "$.error.details.reason")).isEqualTo("not_booked");
    }

    // ---- FR-APT-030: the ticket's priority class, and the recorded check-in variance ------------------------------

    @Test
    void theTicketCarriesTheAppointmentsPriorityClassAndRecordsTheCheckinVariance() throws Exception {
        Setup s = setup("PRI");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID senior = newPriorityClass(admin, "Senior citizen", 20);
        UUID visitor = newVisitor("Boro Bhai", "01700000340");
        MvcResult booked = book(reception, s.service(), visitor, senior);
        UUID appointmentId = id(booked, "$.id");
        String reference = field(booked, "$.reference_code");
        clock.set(SLOT_START.plusSeconds(300)); // 5 minutes late, still within grace

        MvcResult checkedIn = checkInReception(reception, reference);

        assertThat(status(checkedIn)).as(body(checkedIn)).isEqualTo(200);
        assertThat((String) field(checkedIn, "$.ticket.priority_class.id")).isEqualTo(senior.toString());
        assertThat(count("appointment", "id = ? AND checkin_variance_seconds = 300", appointmentId)).isEqualTo(1);
    }

    @Test
    void anEarlyCheckinRecordsANegativeVariance() throws Exception {
        Setup s = setup("NEGVAR");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID visitor = newVisitor("Karim", "01700000341");
        MvcResult booked = book(reception, s.service(), visitor, null);
        UUID appointmentId = id(booked, "$.id");
        String reference = field(booked, "$.reference_code");
        clock.set(SLOT_START.minusSeconds(600)); // 10 minutes early, within the window

        MvcResult checkedIn = checkInReception(reception, reference);

        assertThat(status(checkedIn)).as(body(checkedIn)).isEqualTo(200);
        assertThat(count("appointment", "id = ? AND checkin_variance_seconds = -600", appointmentId)).isEqualTo(1);
    }

    // ---- FR-QUE-020, FR-APT-032: effective wait from the later of slot time and check-in, plus the bonus -----------

    @Test
    void effectiveWaitIsMeasuredFromTheLaterOfSlotTimeAndCheckInPlusTheConfiguredBonus() throws Exception {
        Setup s = setup("WAIT");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID visitor = newVisitor("Karim", "01700000350");
        String reference = bookedReferenceCode(reception, s.service(), visitor);
        clock.set(SLOT_START.minusSeconds(20 * 60)); // 20 minutes early, within the window
        MvcResult checkedIn = checkInReception(reception, reference);
        assertThat(status(checkedIn)).as(body(checkedIn)).isEqualTo(200);

        MvcResult rightAfterCheckin = dryRun(admin, s.service());
        assertThat((Double) field(rightAfterCheckin, "$.tickets[0].terms.effective_wait_minutes"))
                .as("the ticket does not wait before the slot itself starts").isZero();
        assertThat((Double) field(rightAfterCheckin, "$.tickets[0].terms.appointment_bonus")).isEqualTo(15.0);

        clock.set(SLOT_START.plusSeconds(3 * 60)); // 3 minutes after the slot started
        MvcResult afterSlotStarts = dryRun(admin, s.service());
        assertThat((Double) field(afterSlotStarts, "$.tickets[0].terms.effective_wait_minutes")).isEqualTo(3.0);
    }

    // ---- FR-APT-031: never displaces a ticket already being served -------------------------------------------------

    @Test
    void aCheckedInAppointmentTicketNeverDisplacesATicketAlreadyBeingServed() throws Exception {
        Setup s = setup("SERVE");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        UUID agentUser = newAgentOnTeam(s.team());
        String agentToken = tokenFor(Role.AGENT, agentUser, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID highPriority = newPriorityClass(admin, "VIP", 500); // a very large head start
        UUID walkInVisitor = newVisitor("Walk-in", "01700000360");
        UUID apptVisitor = newVisitor("Appointment", "01700000361");

        MvcResult issued = call(post("/api/v1/tickets").header("Idempotency-Key", UUID.randomUUID().toString()), reception,
                "{\"service_id\":\"" + s.service() + "\",\"origin_channel\":\"reception\",\"visitor_id\":\"" + walkInVisitor + "\"}");
        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        UUID servedTicketId = id(issued, "$.id");

        MvcResult opened = call(post("/api/v1/sessions"), agentToken, "{\"counter_id\":\"" + s.counter() + "\"}");
        assertThat(status(opened)).as(body(opened)).isEqualTo(201);
        UUID session = id(opened, "$.id");
        assertThat(status(call(post("/api/v1/sessions/" + session + "/next"), agentToken, null))).isEqualTo(200);
        assertThat(status(call(post("/api/v1/sessions/" + session + "/serve"), agentToken, null))).isEqualTo(200);
        assertThat(count("ticket", "id = ? AND state = 'serving'", servedTicketId)).isEqualTo(1);

        MvcResult booked = book(reception, s.service(), apptVisitor, highPriority);
        assertThat(status(booked)).as(body(booked)).isEqualTo(201);
        String reference = field(booked, "$.reference_code");
        clock.set(SLOT_START);
        MvcResult checkedIn = checkInReception(reception, reference);
        assertThat(status(checkedIn)).as(body(checkedIn)).isEqualTo(200);

        assertThat(count("ticket", "id = ? AND state = 'serving' AND counter_session_id = ?", servedTicketId, session))
                .as("the ticket being served is untouched by a later, higher-scored waiting ticket").isEqualTo(1);
    }

    // ---- not found, wrong state, permission and scope ----------------------------------------------------------

    @Test
    void checkInOfAnUnknownReferenceCodeIsNotFound() throws Exception {
        Setup s = setup("NF");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());

        assertThat(status(checkInReception(reception, "A-DOESNOTEXIST"))).isEqualTo(404);
    }

    @Test
    void checkInOfAnAlreadyConvertedAppointmentIsRefused() throws Exception {
        Setup s = setup("DUP");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID visitor = newVisitor("Karim", "01700000370");
        String reference = bookedReferenceCode(reception, s.service(), visitor);
        clock.set(SLOT_START);
        assertThat(status(checkInReception(reception, reference))).isEqualTo(200);

        MvcResult refused = checkInReception(reception, reference);

        assertThat(status(refused)).as(body(refused)).isEqualTo(409);
        assertThat((String) field(refused, "$.error.details.reason")).isEqualTo("not_booked");
    }

    @Test
    void checkInRequiresThePermissionAndIsScopedToTheServicesSite() throws Exception {
        Setup s = setup("SCOPE");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "11:00", 30, 2);
        UUID visitor = newVisitor("Karim", "01700000380");
        String reference = bookedReferenceCode(reception, s.service(), visitor);
        String agentToken = token(Role.AGENT, s.site());
        String otherSiteReception = token(Role.RECEPTION_OPERATOR, UUID.randomUUID());
        clock.set(SLOT_START);

        assertThat(status(checkInReception(agentToken, reference))).as("agent has no appointment:checkin permission").isEqualTo(403);
        assertThat(status(checkInReception(otherSiteReception, reference))).as("reception scoped to a different site").isEqualTo(403);
    }
}
