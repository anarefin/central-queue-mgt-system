package com.qms.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Ticket 32 against real PostgreSQL with a clock the test moves: availability at Service, Team and Agent level, most
 * specific winning (FR-APT-001), slot templates (FR-APT-002), exceptions and the admin override of business hours and
 * holidays (FR-APT-003, FR-APT-004), the booking horizon and minimum lead time (FR-APT-005), and the search by
 * Service then date (FR-APT-010).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, AppointmentAvailabilityIT.Clocks.class})
class AppointmentAvailabilityIT {

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
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-appointment-availability");
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

    private UUID newService(UUID group, String prefix, String bookingMode) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\",\"bn\":\"পরামর্শ\"}'::jsonb, ?, 10, 30, '[\"reception\",\"kiosk\"]'::jsonb, ?, true)",
                id, group, prefix, bookingMode);
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

    /** A site with one service group, one service (booking_mode 'both'), its team and one rostered agent. */
    private Setup setup(String prefix) {
        UUID site = newSite();
        UUID group = newGroup(site, "G" + prefix);
        UUID service = newService(group, prefix, "both");
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

    private String template(int weekday, String start, String end, int slotMinutes, int capacity) {
        return String.format(
                "{\"items\":[{\"weekday\":%d,\"start\":\"%s\",\"end\":\"%s\",\"slot_minutes\":%d,\"capacity\":%d}]}", weekday, start, end, slotMinutes, capacity);
    }

    private MvcResult putServiceTemplate(String admin, UUID serviceId, int weekday, String start, String end, int slotMinutes, int capacity) throws Exception {
        return call(put("/api/v1/appointment-templates/service/" + serviceId), admin, template(weekday, start, end, slotMinutes, capacity));
    }

    private MvcResult search(String token, UUID serviceId, String date) throws Exception {
        return call(get("/api/v1/services/" + serviceId + "/appointments/availability?date=" + date), token, null);
    }

    // ---- FR-APT-002, FR-APT-010: templates and search ---------------------------------------------------------

    @Test
    void aServiceLevelTemplateProducesSlotsWithRemainingCapacityOnTheSearch() throws Exception {
        Setup s = setup("SV");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        MvcResult set = putServiceTemplate(admin, s.service(), 1, "09:00", "10:00", 30, 2);
        assertThat(status(set)).as(body(set)).isEqualTo(200);

        MvcResult found = search(reception, s.service(), MONDAY);

        assertThat(status(found)).as(body(found)).isEqualTo(200);
        assertThat((String) field(found, "$.service_id")).isEqualTo(s.service().toString());
        assertThat((List<String>) field(found, "$.slots[*].start")).containsExactly("09:00", "09:30");
        assertThat((List<String>) field(found, "$.slots[*].end")).containsExactly("09:30", "10:00");
        assertThat((List<Integer>) field(found, "$.slots[*].remaining_capacity")).containsExactly(2, 2);
    }

    @Test
    void readingBackAServiceLevelTemplateReturnsWhatWasSet() throws Exception {
        Setup s = setup("RB");
        String admin = token(Role.ORG_ADMIN, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "10:00", 30, 2);

        MvcResult read = call(get("/api/v1/appointment-templates/service/" + s.service()), admin, null);

        assertThat(status(read)).as(body(read)).isEqualTo(200);
        assertThat((Integer) field(read, "$.items[0].weekday")).isEqualTo(1);
        assertThat((String) field(read, "$.items[0].start")).isEqualTo("09:00");
        assertThat((Integer) field(read, "$.items[0].slot_minutes")).isEqualTo(30);
        assertThat((Integer) field(read, "$.items[0].capacity")).isEqualTo(2);
    }

    @Test
    void aValidityRangeExcludesTheTemplateOutsideItsDates() throws Exception {
        Setup s = setup("VR");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        MvcResult set = call(
                put("/api/v1/appointment-templates/service/" + s.service()),
                admin,
                "{\"items\":[{\"weekday\":1,\"start\":\"09:00\",\"end\":\"09:30\",\"slot_minutes\":30,\"capacity\":1,\"valid_from\":\"2026-01-01\",\"valid_to\":\"2026-09-01\"}]}");
        assertThat(status(set)).as(body(set)).isEqualTo(200);

        MvcResult found = search(reception, s.service(), MONDAY);

        assertThat((List<String>) field(found, "$.slots[*].start")).as("2026-09-21 is after the template's valid_to").isEmpty();
    }

    // ---- FR-APT-001: most specific level wins ------------------------------------------------------------------

    @Test
    void teamLevelTemplateOverridesServiceLevelForThatWeekday() throws Exception {
        Setup s = setup("MS");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "08:00", "08:30", 30, 1);
        MvcResult set = call(put("/api/v1/appointment-templates/team/" + s.team()), admin, template(1, "09:00", "09:30", 30, 5));
        assertThat(status(set)).as(body(set)).isEqualTo(200);

        MvcResult found = search(reception, s.service(), MONDAY);

        assertThat((List<String>) field(found, "$.slots[*].start")).as("the team's slots replace the service's, not add to them").containsExactly("09:00");
        assertThat((List<Integer>) field(found, "$.slots[*].remaining_capacity")).containsExactly(5);
    }

    @Test
    void agentLevelTemplatesAreDefinableReadableAndRemovable() throws Exception {
        Setup s = setup("AG");
        String admin = token(Role.ORG_ADMIN, s.site());
        MvcResult set = call(put("/api/v1/appointment-templates/agent/" + s.agent()), admin, template(1, "13:00", "13:30", 30, 1));
        assertThat(status(set)).as(body(set)).isEqualTo(200);

        MvcResult read = call(get("/api/v1/appointment-templates/agent/" + s.agent()), admin, null);
        assertThat((List<String>) field(read, "$.items[*].start")).containsExactly("13:00");

        MvcResult cleared = call(put("/api/v1/appointment-templates/agent/" + s.agent()), admin, "{\"items\":[]}");
        assertThat(status(cleared)).isEqualTo(200);
        assertThat((List<String>) field(call(get("/api/v1/appointment-templates/agent/" + s.agent()), admin, null), "$.items[*].start")).isEmpty();
    }

    // ---- FR-APT-003, FR-APT-004: exceptions and the admin override ---------------------------------------------

    @Test
    void aBlockedExceptionSuppressesTheDateEntirely() throws Exception {
        Setup s = setup("BL");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "10:00", 30, 2);
        MvcResult added = call(
                post("/api/v1/appointment-exceptions/service/" + s.service()), admin, "{\"date\":\"" + MONDAY + "\",\"type\":\"blocked\"}");
        assertThat(status(added)).as(body(added)).isEqualTo(201);

        assertThat((List<String>) field(search(reception, s.service(), MONDAY), "$.slots[*].start")).isEmpty();
    }

    @Test
    void aReducedCapacityExceptionCapsCapacityForThatDateOnly() throws Exception {
        Setup s = setup("RC");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 5);
        call(post("/api/v1/appointment-exceptions/service/" + s.service()), admin, "{\"date\":\"" + MONDAY + "\",\"type\":\"reduced_capacity\",\"capacity\":1}");

        assertThat((List<Integer>) field(search(reception, s.service(), MONDAY), "$.slots[*].remaining_capacity")).containsExactly(1);

        String nextMonday = "2026-09-28";
        assertThat((List<Integer>) field(search(reception, s.service(), nextMonday), "$.slots[*].remaining_capacity"))
                .as("the reduction is only for the one date").containsExactly(5);
    }

    @Test
    void aFullHolidaySuppressesSlotsAndAnExtraExceptionOverridesIt() throws Exception {
        Setup s = setup("HO");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "10:00", 30, 2);
        MvcResult holiday = call(post("/api/v1/sites/" + s.site() + "/holidays"), admin, "{\"date\":\"" + MONDAY + "\",\"name\":\"Founders Day\"}");
        assertThat(status(holiday)).as(body(holiday)).isEqualTo(201);

        assertThat((List<String>) field(search(reception, s.service(), MONDAY), "$.slots[*].start")).as("suppressed by the holiday").isEmpty();

        MvcResult extra = call(
                post("/api/v1/appointment-exceptions/service/" + s.service()),
                admin,
                "{\"date\":\"" + MONDAY + "\",\"type\":\"extra\",\"start\":\"14:00\",\"end\":\"14:30\",\"slot_minutes\":30,\"capacity\":3}");
        assertThat(status(extra)).as(body(extra)).isEqualTo(201);

        MvcResult overridden = search(reception, s.service(), MONDAY);
        assertThat((List<String>) field(overridden, "$.slots[*].start")).as("the admin override opens its own window despite the holiday").containsExactly("14:00");
        assertThat((List<Integer>) field(overridden, "$.slots[*].remaining_capacity")).containsExactly(3);
    }

    @Test
    void aWeekdayMissingFromConfiguredBusinessHoursIsSuppressed() throws Exception {
        Setup s = setup("WD");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "10:00", 30, 2);
        // Only Sunday (weekday 7) is configured, so Monday is a closed day.
        call(put("/api/v1/sites/" + s.site() + "/hours"), admin, "{\"days\":[{\"weekday\":7,\"open\":\"09:00\",\"close\":\"17:00\"}]}");

        assertThat((List<String>) field(search(reception, s.service(), MONDAY), "$.slots[*].start")).isEmpty();
    }

    @Test
    void duplicateExceptionsForTheSameDateAreConflict() throws Exception {
        Setup s = setup("DX");
        String admin = token(Role.ORG_ADMIN, s.site());
        call(post("/api/v1/appointment-exceptions/service/" + s.service()), admin, "{\"date\":\"" + MONDAY + "\",\"type\":\"blocked\"}");

        MvcResult again = call(post("/api/v1/appointment-exceptions/service/" + s.service()), admin, "{\"date\":\"" + MONDAY + "\",\"type\":\"blocked\"}");

        assertThat(status(again)).isEqualTo(409);
        assertThat((String) field(again, "$.error.details.reason")).isEqualTo("exception_exists");
    }

    @Test
    void anExceptionCanBeRemovedAfterWhichTheDateIsNormalAgain() throws Exception {
        Setup s = setup("RM");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 2);
        MvcResult added = call(post("/api/v1/appointment-exceptions/service/" + s.service()), admin, "{\"date\":\"" + MONDAY + "\",\"type\":\"blocked\"}");
        UUID exceptionId = UUID.fromString(field(added, "$.id"));

        MvcResult removed = call(delete("/api/v1/appointment-exceptions/service/" + s.service() + "/" + exceptionId), admin, null);
        assertThat(status(removed)).isEqualTo(204);

        assertThat((List<String>) field(search(reception, s.service(), MONDAY), "$.slots[*].start")).containsExactly("09:00");
    }

    @Test
    void anExceptionsNoteAcceptsBothInstalledLanguagesAndRejectsAnUnknownOne() throws Exception {
        Setup s = setup("NT");
        String admin = token(Role.ORG_ADMIN, s.site());
        MvcResult created = call(
                post("/api/v1/appointment-exceptions/service/" + s.service()),
                admin,
                "{\"date\":\"" + MONDAY + "\",\"type\":\"blocked\",\"note_i18n\":{\"en\":\"Closed for training\",\"bn\":\"প্রশিক্ষণের জন্য বন্ধ\"}}");
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        assertThat((String) field(created, "$.note_i18n.en")).isEqualTo("Closed for training");
        assertThat((String) field(created, "$.note_i18n.bn")).isEqualTo("প্রশিক্ষণের জন্য বন্ধ");

        MvcResult rejected = call(
                post("/api/v1/appointment-exceptions/service/" + s.service()),
                token(Role.ORG_ADMIN, s.site()),
                "{\"date\":\"2026-09-22\",\"type\":\"blocked\",\"note_i18n\":{\"fr\":\"Fermé\"}}");
        assertThat(status(rejected)).isEqualTo(400);
        assertThat((String) field(rejected, "$.error.details.fields[0].field")).isEqualTo("note_i18n");
    }

    // ---- FR-APT-005: booking horizon and minimum lead time -----------------------------------------------------

    @Test
    void defaultsAreThirtyDaysAndTwoHoursWhenNeverConfigured() throws Exception {
        Setup s = setup("DF");
        MvcResult read = call(get("/api/v1/services/" + s.service() + "/appointment-settings"), token(Role.ORG_ADMIN, s.site()), null);

        assertThat(status(read)).as(body(read)).isEqualTo(200);
        assertThat((Integer) field(read, "$.booking_horizon_days")).isEqualTo(30);
        assertThat((Integer) field(read, "$.min_lead_time_minutes")).isEqualTo(120);
    }

    @Test
    void aDateBeyondTheBookingHorizonReturnsNoSlots() throws Exception {
        Setup s = setup("HZ");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 2);
        MvcResult set = call(put("/api/v1/services/" + s.service() + "/appointment-settings"), admin, "{\"booking_horizon_days\":1,\"min_lead_time_minutes\":0}");
        assertThat(status(set)).as(body(set)).isEqualTo(200);

        assertThat((List<String>) field(search(reception, s.service(), MONDAY), "$.slots[*].start")).as("Monday is more than 1 day past Saturday").isEmpty();
    }

    @Test
    void aSlotStartingBeforeTheMinimumLeadTimeIsExcluded() throws Exception {
        Setup s = setup("LT");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        // Saturday itself (weekday 6), BASE is 10:00 Dhaka: the 10:00 slot is too soon, the 10:15 one is not.
        putServiceTemplate(admin, s.service(), 6, "10:00", "10:30", 15, 2);
        call(put("/api/v1/services/" + s.service() + "/appointment-settings"), admin, "{\"booking_horizon_days\":30,\"min_lead_time_minutes\":10}");

        MvcResult found = search(reception, s.service(), "2026-09-19");

        assertThat((List<String>) field(found, "$.slots[*].start")).as("only the slot starting at least 10 minutes out remains").containsExactly("10:15");
    }

    // ---- FR-APT-010: only slots with remaining capacity ---------------------------------------------------------

    @Test
    void aSlotReducedToZeroCapacityIsExcludedFromTheSearch() throws Exception {
        Setup s = setup("ZC");
        String admin = token(Role.ORG_ADMIN, s.site());
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 2);
        call(post("/api/v1/appointment-exceptions/service/" + s.service()), admin, "{\"date\":\"" + MONDAY + "\",\"type\":\"reduced_capacity\",\"capacity\":0}");

        assertThat((List<String>) field(search(reception, s.service(), MONDAY), "$.slots[*].start")).isEmpty();
    }

    @Test
    void aWalkInOnlyServiceHasNoAppointmentAvailability() throws Exception {
        UUID site = newSite();
        UUID group = newGroup(site, "GWK");
        UUID service = newService(group, "WK", "walk_in_only");
        String admin = token(Role.ORG_ADMIN, site);
        String reception = token(Role.RECEPTION_OPERATOR, site);
        putServiceTemplate(admin, service, 1, "09:00", "09:30", 30, 2);

        assertThat((List<String>) field(search(reception, service, MONDAY), "$.slots[*].start")).isEmpty();
    }

    // ---- permissions, scope and audit (DoD, SRS §27.5) ----------------------------------------------------------

    @Test
    void theTemplateEndpointsArePermissionCheckedAndScopedToTheCallersSites() throws Exception {
        Setup s = setup("PM");
        Setup theirs = setup("PT");
        String reception = token(Role.RECEPTION_OPERATOR, s.site());
        String orgAdminOther = token(Role.ORG_ADMIN, theirs.site());
        String orgAdminMine = token(Role.ORG_ADMIN, s.site());

        assertThat(status(call(get("/api/v1/appointment-templates/service/" + s.service()), reception, null))).as("wrong permission").isEqualTo(403);
        assertThat(status(call(get("/api/v1/appointment-templates/service/" + s.service()), orgAdminOther, null))).as("outside caller's sites").isEqualTo(403);
        assertThat(status(call(get("/api/v1/appointment-templates/service/" + s.service()), orgAdminMine, null))).isEqualTo(200);
        assertThat(status(call(get("/api/v1/appointment-templates/service/" + s.service()), null, null))).isEqualTo(401);
    }

    @Test
    void theSearchEndpointAcceptsAnyAuthenticatedCallerButNotAnAnonymousOne() throws Exception {
        Setup s = setup("SA");
        putServiceTemplate(token(Role.ORG_ADMIN, s.site()), s.service(), 1, "09:00", "09:30", 30, 2);

        assertThat(status(search(token(Role.RECEPTION_OPERATOR, s.site()), s.service(), MONDAY))).isEqualTo(200);
        assertThat(status(search(token(Role.AGENT, s.site()), s.service(), MONDAY))).isEqualTo(200);
        assertThat(status(search(null, s.service(), MONDAY))).isEqualTo(401);
    }

    @Test
    void settingATemplateWritesAnAuditEntryAndANoOpWritesNothing() throws Exception {
        Setup s = setup("AU");
        String admin = token(Role.ORG_ADMIN, s.site());

        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 2);
        assertThat(count("audit_log", "action = 'appointment_slot_template.updated' AND entity_id = ?", s.service())).isEqualTo(1);

        putServiceTemplate(admin, s.service(), 1, "09:00", "09:30", 30, 2);
        assertThat(count("audit_log", "action = 'appointment_slot_template.updated' AND entity_id = ?", s.service())).as("changing nothing writes nothing").isEqualTo(1);
    }

    @Test
    void exceptionsAndSettingsChangesAlsoWriteAuditEntries() throws Exception {
        Setup s = setup("AX");
        String admin = token(Role.ORG_ADMIN, s.site());

        MvcResult added = call(post("/api/v1/appointment-exceptions/service/" + s.service()), admin, "{\"date\":\"" + MONDAY + "\",\"type\":\"blocked\"}");
        UUID exceptionId = UUID.fromString(field(added, "$.id"));
        assertThat(count("audit_log", "action = 'appointment_exception.created' AND entity_id = ?", exceptionId)).isEqualTo(1);

        call(delete("/api/v1/appointment-exceptions/service/" + s.service() + "/" + exceptionId), admin, null);
        assertThat(count("audit_log", "action = 'appointment_exception.deleted' AND entity_id = ?", exceptionId)).isEqualTo(1);

        call(put("/api/v1/services/" + s.service() + "/appointment-settings"), admin, "{\"booking_horizon_days\":10,\"min_lead_time_minutes\":15}");
        assertThat(count("audit_log", "action = 'appointment_settings.updated' AND entity_id = ?", s.service())).isEqualTo(1);
    }

    // ---- validation --------------------------------------------------------------------------------------------

    @Test
    void malformedTemplatesAndExceptionsAreValidationFailedNamingTheField() throws Exception {
        Setup s = setup("VF");
        String admin = token(Role.ORG_ADMIN, s.site());

        assertThat((String) field(call(put("/api/v1/appointment-templates/service/" + s.service()), admin, template(8, "09:00", "10:00", 30, 1)), "$.error.details.fields[0].field"))
                .isEqualTo("weekday");
        assertThat((String) field(call(put("/api/v1/appointment-templates/service/" + s.service()), admin, template(1, "10:00", "09:00", 30, 1)), "$.error.details.fields[0].field"))
                .isEqualTo("end");
        assertThat((String) field(call(put("/api/v1/appointment-templates/service/" + s.service()), admin, template(1, "09:00", "10:00", 0, 1)), "$.error.details.fields[0].field"))
                .isEqualTo("slot_minutes");
        MvcResult badExtra = call(
                post("/api/v1/appointment-exceptions/service/" + s.service()), admin, "{\"date\":\"" + MONDAY + "\",\"type\":\"extra\"}");
        assertThat((String) field(badExtra, "$.error.details.fields[0].field")).isEqualTo("start");
        MvcResult unknownLevel = call(get("/api/v1/appointment-templates/site/" + s.service()), admin, null);
        assertThat(status(unknownLevel)).isEqualTo(400);
    }
}
