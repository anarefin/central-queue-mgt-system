package com.qms.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.issuance.ActorType;
import com.qms.issuance.Channels;
import com.qms.issuance.IssuanceService;
import com.qms.issuance.IssueCommand;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Ticket 20 against real PostgreSQL: what the console shows of a called ticket and its visitor (FR-AGT-030, FR-AGT-034), what the
 * completion records (FR-AGT-032, covered in full by {@code SessionIT}) and the Agent's own day (FR-AGT-040). The Agent role is
 * configured to see no visitor name; the Team Admin role is not configured and sees the full set.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, ConsoleContextIT.Clocks.class})
class ConsoleContextIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    /** Saturday 19 September 2026, 16:00 in Dhaka (UTC+6). */
    static final Instant BASE = Instant.parse("2026-09-19T10:00:00Z");
    /** Midnight at the start of that day in Dhaka. */
    static final Instant DHAKA_MIDNIGHT = Instant.parse("2026-09-18T18:00:00Z");

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
        registry.add("qms.queue.call-timeout-check-cron", () -> "-");
        registry.add("qms.console.visitor-fields.agent", () -> "code,category,purpose_note");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-console-context");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssuanceService issuance;
    @Autowired MutableClock clock;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private record World(UUID site, UUID group, UUID service, UUID counter) {}

    private record Staff(UUID id, String token) {}

    private World world() {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "S-" + site.toString().substring(0, 8));
        UUID zone = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, floor_label) VALUES (?, ?, 'Hall', '1st')", zone, site);
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'G')", group, site);
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, 'Outpatient team')", UUID.randomUUID(), group);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, 'A', 10, 30, '[\"reception\",\"kiosk\",\"appointment_checkin\"]'::jsonb, 'both')",
                service, group);
        UUID counter = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, 'Desk 1')", counter, zone);
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", counter, service);
        return new World(site, group, service, counter);
    }

    private UUID outcome(UUID service, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO outcome_code (id, service_id, code, label_i18n) VALUES (?, ?, ?, ?::jsonb)", id, service, code, "{\"en\":\"" + code + "\"}");
        return id;
    }

    /** A signed-in staff member on the world's team. Their token is minted at real time, then the clock goes back. */
    private Staff staff(Role role, World w) throws Exception {
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            UUID user = UUID.randomUUID();
            String username = role.wire() + "-" + user;
            jdbc.update(
                    "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                    user, username, new BCryptPasswordEncoder(12).encode(PASSWORD), role.wire(), "en");
            jdbc.update(connection -> {
                var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, user);
                ps.setString(3, role.wire());
                ps.setArray(4, connection.createArrayOf("uuid", new UUID[] {w.site()}));
                ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
                return ps;
            });
            jdbc.update("INSERT INTO team_member (team_id, user_id) SELECT id, ? FROM team WHERE service_group_id = ?", user, w.group());
            MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
            return new Staff(user, JsonPath.read(body(result), "$.access_token"));
        } finally {
            clock.set(testTime);
        }
    }

    private UUID visitor(String code, String name, String category) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO visitor (id, external_code, name, category, created_at) VALUES (?, ?, ?, ?, now())", id, code, name, category);
        return id;
    }

    /** Issues a ticket {@code minutesAgo} minutes before BASE through the channel, with the visitor and note when given. */
    private UUID issue(World w, String channel, int minutesAgo, UUID visitor, String purposeNote) {
        clock.set(BASE.minus(Duration.ofMinutes(minutesAgo)));
        try {
            String token = issuance.issue(new IssueCommand(w.service(), channel, UUID.randomUUID(), ActorType.SYSTEM, null)).tokenNumber();
            UUID id = jdbc.queryForObject("SELECT id FROM ticket WHERE token_number = ? AND service_id = ?", UUID.class, token, w.service());
            if (visitor != null) jdbc.update("UPDATE ticket SET visitor_id = ? WHERE id = ?", visitor, id);
            if (purposeNote != null) jdbc.update("UPDATE ticket SET purpose_note = ? WHERE id = ?", purposeNote, id);
            return id;
        } finally {
            clock.set(BASE);
        }
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andReturn();
    }

    private UUID opened(Staff who, World w) throws Exception {
        MvcResult result = call(post("/api/v1/sessions"), who.token(), "{\"counter_id\":\"" + w.counter() + "\"}");
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        return UUID.fromString(field(result, "$.id"));
    }

    private MvcResult next(Staff who, UUID session) throws Exception {
        MvcResult called = call(post("/api/v1/sessions/" + session + "/next"), who.token(), null);
        assertThat(called.getResponse().getStatus()).as(body(called)).isEqualTo(200);
        return called;
    }

    /** Calls the next ticket, serves it for {@code seconds}, and completes it. */
    private void serveFor(Staff who, UUID session, UUID outcome, int seconds) throws Exception {
        next(who, session);
        assertThat(call(post("/api/v1/sessions/" + session + "/serve"), who.token(), null).getResponse().getStatus()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(seconds));
        MvcResult done = call(post("/api/v1/sessions/" + session + "/complete"), who.token(), "{\"outcome_code_id\":\"" + outcome + "\"}");
        assertThat(done.getResponse().getStatus()).as(body(done)).isEqualTo(200);
    }

    private MvcResult stats(String token) throws Exception {
        return call(get("/api/v1/sessions/stats"), token, null);
    }

    private static java.time.OffsetDateTime at(Instant instant) {
        return instant.atOffset(java.time.ZoneOffset.UTC);
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static <T> T field(MvcResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    // ---- FR-AGT-030, FR-AGT-034: what the console shows of a called ticket -------------------------------------

    @Test
    void aCalledTicketShowsTheVisitorTheServiceTheNoteTheWaitSoFarTheChannelAndWhetherItIsAnAppointment() throws Exception {
        World w = world();
        UUID asar = visitor("0062", "Asar Ali", "Children Tailoring");
        issue(w, Channels.APPOINTMENT_CHECKIN, 12, asar, "Follow-up on the scan");
        Staff lead = staff(Role.TEAM_ADMIN, w);
        UUID session = opened(lead, w);

        MvcResult called = next(lead, session);

        assertThat((String) field(called, "$.ticket.visitor.code")).isEqualTo("0062");
        assertThat((String) field(called, "$.ticket.visitor.name")).isEqualTo("Asar Ali");
        assertThat((String) field(called, "$.ticket.visitor.category")).isEqualTo("Children Tailoring");
        assertThat((String) field(called, "$.ticket.purpose_note")).isEqualTo("Follow-up on the scan");
        assertThat((String) field(called, "$.ticket.service.name_i18n.en")).isEqualTo("Consultation");
        assertThat((String) field(called, "$.ticket.origin_channel")).isEqualTo("appointment_checkin");
        assertThat((Boolean) field(called, "$.ticket.is_appointment")).isTrue();
        assertThat((Integer) field(called, "$.ticket.wait_seconds")).as("waited 12 minutes before the call").isEqualTo(720);
        assertThat((String) field(called, "$.ticket.token_number")).isNotBlank();

        // The screen rebuilds itself from the session after a refresh (FR-AGT-004) and must find the same context.
        MvcResult restored = call(get("/api/v1/sessions/current"), lead.token(), null);
        assertThat((String) JsonPath.read(body(restored), "$.ticket.visitor.name")).isEqualTo("Asar Ali");
    }

    @Test
    void aWalkInWithNoVisitorRecordAndNoNoteHasNoVisitorContextAndIsNotAnAppointment() throws Exception {
        World w = world();
        issue(w, Channels.RECEPTION, 3, null, null);
        Staff lead = staff(Role.TEAM_ADMIN, w);
        UUID session = opened(lead, w);

        MvcResult called = next(lead, session);

        assertThat((Boolean) field(called, "$.ticket.is_appointment")).isFalse();
        assertThat((String) field(called, "$.ticket.origin_channel")).isEqualTo("reception");
        assertThat(JsonPath.<Object>read(body(called), "$.ticket").toString()).doesNotContain("visitor", "purpose_note");
    }

    @Test
    void onlyTheVisitorFieldsConfiguredForTheCallersRoleAreSentAndTheOthersAreNotInTheResponseAtAll() throws Exception {
        World w = world();
        UUID asar = visitor("0062", "Asar Ali", "Children Tailoring");
        issue(w, Channels.RECEPTION, 5, asar, "Follow-up on the scan");
        Staff agent = staff(Role.AGENT, w);
        UUID session = opened(agent, w);

        MvcResult called = next(agent, session);

        assertThat((String) field(called, "$.ticket.visitor.code")).isEqualTo("0062");
        assertThat((String) field(called, "$.ticket.visitor.category")).isEqualTo("Children Tailoring");
        assertThat((String) field(called, "$.ticket.purpose_note")).isEqualTo("Follow-up on the scan");
        assertThat(body(called)).as("the name is left out of the response, not hidden by the screen").doesNotContain("Asar Ali").doesNotContain("\"name\"");
        assertThat(field(call(get("/api/v1/sessions/current"), agent.token(), null), "$.ticket.visitor.keys()").toString()).doesNotContain("name");
    }

    @Test
    void aTicketHeldByTheAgentKeepsTheSameVisitorFieldsAsTheOneInService() throws Exception {
        World w = world();
        UUID asar = visitor("0062", "Asar Ali", "Children Tailoring");
        issue(w, Channels.RECEPTION, 5, asar, null);
        Staff agent = staff(Role.AGENT, w);
        UUID session = opened(agent, w);
        next(agent, session);
        call(post("/api/v1/sessions/" + session + "/serve"), agent.token(), null);

        MvcResult held = call(post("/api/v1/sessions/" + session + "/hold"), agent.token(), null);

        assertThat(held.getResponse().getStatus()).as(body(held)).isEqualTo(200);
        assertThat((String) field(held, "$.held[0].visitor.code")).isEqualTo("0062");
        assertThat(body(held)).doesNotContain("Asar Ali");
    }

    // ---- FR-AGT-032: completion records the outcome and a note ------------------------------------------------

    @Test
    void completingRecordsTheOutcomeAndTheOptionalNoteOnTheTicketAndItsEvent() throws Exception {
        World w = world();
        UUID resolved = outcome(w.service(), "resolved");
        UUID ticket = issue(w, Channels.RECEPTION, 4, null, "Needs a certificate");
        Staff agent = staff(Role.AGENT, w);
        UUID session = opened(agent, w);
        next(agent, session);
        call(post("/api/v1/sessions/" + session + "/serve"), agent.token(), null);

        MvcResult done = call(post("/api/v1/sessions/" + session + "/complete"), agent.token(), "{\"outcome_code_id\":\"" + resolved + "\",\"note\":\"  Certificate issued \"}");

        assertThat(done.getResponse().getStatus()).as(body(done)).isEqualTo(200);
        var row = jdbc.queryForMap("SELECT state, outcome_code_id, note, purpose_note FROM ticket WHERE id = ?", ticket);
        assertThat(row.get("state")).isEqualTo("completed");
        assertThat(row.get("outcome_code_id")).isEqualTo(resolved);
        assertThat(row.get("note")).as("the completion note is the Agent's; the purpose note stays the visitor's").isEqualTo("Certificate issued");
        assertThat(row.get("purpose_note")).isEqualTo("Needs a certificate");
        assertThat(jdbc.queryForObject("SELECT payload->>'outcome_code' FROM ticket_event WHERE ticket_id = ? AND to_state = 'completed'", String.class, ticket)).isEqualTo("resolved");
    }

    // ---- FR-AGT-040: the Agent's own day ---------------------------------------------------------------------

    @Test
    void theAgentSeesTheirOwnDayServedWaitingAverageServiceTimeAndBreakTimeAndNothingOfAnyoneElse() throws Exception {
        World w = world();
        UUID resolved = outcome(w.service(), "resolved");
        UUID lunch = UUID.randomUUID();
        jdbc.update("INSERT INTO break_type (id, name_i18n, max_minutes, active, created_at, updated_at) VALUES (?, '{\"en\":\"Lunch\"}'::jsonb, 30, true, now(), now())", lunch);
        for (int i = 0; i < 6; i++) issue(w, Channels.RECEPTION, 30 - i, null, null);
        Staff rina = staff(Role.AGENT, w);
        Staff other = staff(Role.TEAM_ADMIN, w);

        // Someone else works at another desk of the same Service and finishes a ticket: it is not Rina's.
        UUID otherDesk = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) SELECT ?, zone_id, 'Desk 2' FROM counter WHERE id = ?", otherDesk, w.counter());
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", otherDesk, w.service());
        MvcResult otherOpened = call(post("/api/v1/sessions"), other.token(), "{\"counter_id\":\"" + otherDesk + "\"}");
        UUID otherSession = UUID.fromString(field(otherOpened, "$.id"));
        serveFor(other, otherSession, resolved, 500);
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/sessions/" + otherSession), other.token(), null);

        UUID session = opened(rina, w);
        assertThat(call(post("/api/v1/sessions/" + session + "/break"), rina.token(), "{\"break_type_id\":\"" + lunch + "\"}").getResponse().getStatus()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(10));
        assertThat(call(post("/api/v1/sessions/" + session + "/break"), rina.token(), null).getResponse().getStatus()).isEqualTo(200);
        serveFor(rina, session, resolved, 60);
        serveFor(rina, session, resolved, 120);

        MvcResult day = stats(rina.token());

        assertThat(day.getResponse().getStatus()).as(body(day)).isEqualTo(200);
        assertThat((Integer) field(day, "$.served")).isEqualTo(2);
        assertThat((Integer) field(day, "$.in_queue")).as("6 issued, 1 served by the other Agent and 2 by Rina").isEqualTo(3);
        assertThat((Integer) field(day, "$.average_service_seconds")).isEqualTo(90);
        assertThat(((Number) field(day, "$.break_seconds")).longValue()).isEqualTo(600);
        assertThat(JsonPath.<java.util.Map<String, Object>>read(body(day), "$").keySet())
                .as("counts of one Agent and nothing to rank them by")
                .containsExactlyInAnyOrder("served", "in_queue", "average_service_seconds", "break_seconds", "as_of");

        // A break in progress counts up to now.
        assertThat(call(post("/api/v1/sessions/" + session + "/break"), rina.token(), "{\"break_type_id\":\"" + lunch + "\"}").getResponse().getStatus()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(5));
        assertThat(((Number) field(stats(rina.token()), "$.break_seconds")).longValue()).isEqualTo(900);

        // The other Agent's day is their own.
        MvcResult theirs = stats(other.token());
        assertThat((Integer) field(theirs, "$.served")).isEqualTo(1);
        assertThat((Integer) field(theirs, "$.average_service_seconds")).isEqualTo(500);
        assertThat(((Number) field(theirs, "$.break_seconds")).longValue()).isZero();
        assertThat((Integer) field(theirs, "$.in_queue")).as("no live session, no Services to be waiting for").isZero();
    }

    @Test
    void theDayIsTheDayAtTheSitesTimeZoneSoTheEarlyHoursCountAndYesterdayEveningDoesNot() throws Exception {
        World w = world();
        UUID resolved = outcome(w.service(), "resolved");
        for (int i = 0; i < 3; i++) issue(w, Channels.RECEPTION, 30, null, null);
        Staff agent = staff(Role.AGENT, w);
        UUID session = opened(agent, w);
        serveFor(agent, session, resolved, 100);
        serveFor(agent, session, resolved, 100);
        serveFor(agent, session, resolved, 100);
        List<UUID> done = jdbc.queryForList("SELECT id FROM ticket WHERE agent_id = ? ORDER BY closed_at, id", UUID.class, agent.id());
        // 00:30 today in Dhaka is 18:30 UTC yesterday: today. 23:30 yesterday in Dhaka is 17:30 UTC: yesterday.
        jdbc.update("UPDATE ticket SET called_at = ?, served_at = ?, closed_at = ? WHERE id = ?", at(DHAKA_MIDNIGHT.plusSeconds(1500)), at(DHAKA_MIDNIGHT.plusSeconds(1600)), at(DHAKA_MIDNIGHT.plusSeconds(1800)), done.get(0));
        jdbc.update("UPDATE ticket SET called_at = ?, served_at = ?, closed_at = ? WHERE id = ?", at(DHAKA_MIDNIGHT.minusSeconds(2000)), at(DHAKA_MIDNIGHT.minusSeconds(1900)), at(DHAKA_MIDNIGHT.minusSeconds(1800)), done.get(1));

        MvcResult day = stats(agent.token());

        assertThat((Integer) field(day, "$.served")).as("today's small hours and this afternoon, not last evening").isEqualTo(2);
    }

    @Test
    void anAgentWithNothingServedYetHasZeroesAndNoAverage() throws Exception {
        World w = world();
        Staff agent = staff(Role.AGENT, w);

        MvcResult day = stats(agent.token());

        assertThat(day.getResponse().getStatus()).isEqualTo(200);
        assertThat((Integer) field(day, "$.served")).isZero();
        assertThat((Object) field(day, "$.average_service_seconds")).isNull();
        assertThat(((Number) field(day, "$.break_seconds")).longValue()).isZero();
    }

    @Test
    void theDayIsPermissionCheckedOnTheServer() throws Exception {
        World w = world();
        Staff reception = staff(Role.RECEPTION_OPERATOR, w);

        assertThat(stats(null).getResponse().getStatus()).as("no token").isEqualTo(401);
        assertThat(stats(reception.token()).getResponse().getStatus()).as("Reception does not serve").isEqualTo(403);
    }
}
