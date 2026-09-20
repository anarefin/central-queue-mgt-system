package com.qms.session;

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
 * Ticket 31 against real PostgreSQL: Reception issues a multi-stop Journey in one action, all its tickets sharing one
 * Visit (FR-ISS-022, ADR-0007); an ordered Journey issues its next stop on completion, inheriting the Priority class
 * (FR-QUE-061); an unordered Journey issues every stop up front and shows which is callable soonest (FR-QUE-062); a
 * called stop pauses the Visit's other waiting stops until the visitor is free (FR-QUE-063, Invariant 1); and the
 * console shows a called stop's other stops and their status (FR-AGT-031).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, JourneyIT.Clocks.class})
class JourneyIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    static final Instant BASE = Instant.parse("2026-09-19T10:00:00Z");

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
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-journey");
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
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private record World(UUID site, UUID zone, UUID group, UUID a, UUID b, UUID counter) {}

    private record Agent(UUID id, String token) {}

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
        UUID a = newService(group, "A", "Registration");
        UUID b = newService(group, "B", "Consultation");
        UUID counter = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, '1')", counter, zone);
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", counter, a);
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", counter, b);
        return new World(site, zone, group, a, b, counter);
    }

    private UUID newService(UUID group, String prefix, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode)"
                        + " VALUES (?, ?, ?::jsonb, ?, 10, 30, '[\"reception\",\"kiosk\"]'::jsonb, 'both')",
                id, group, "{\"en\":\"" + name + "\"}", prefix);
        return id;
    }

    private Agent user(Role role, UUID site, UUID teamOf) throws Exception {
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
                ps.setArray(4, connection.createArrayOf("uuid", new UUID[] {site}));
                ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
                return ps;
            });
            if (teamOf != null) {
                jdbc.update("INSERT INTO team_member (team_id, user_id) SELECT id, ? FROM team WHERE service_group_id = ?", user, teamOf);
            }
            MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
            assertThat(status(result)).as(body(result)).isEqualTo(200);
            return new Agent(user, JsonPath.read(body(result), "$.access_token"));
        } finally {
            clock.set(testTime);
        }
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andReturn();
    }

    private void enableJourneys(Agent admin) throws Exception {
        MvcResult result = call(put("/api/v1/journey-settings"), admin.token(), "{\"enabled\":true}");
        assertThat(status(result)).as(body(result)).isEqualTo(200);
    }

    private MvcResult issueJourney(Agent reception, String json) throws Exception {
        return call(post("/api/v1/journeys").header("Idempotency-Key", "journey-" + UUID.randomUUID()), reception.token(), json);
    }

    private UUID openSession(Agent agent, UUID counter) throws Exception {
        MvcResult result = call(post("/api/v1/sessions"), agent.token(), "{\"counter_id\":\"" + counter + "\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(201);
        return UUID.fromString(field(result, "$.id"));
    }

    private MvcResult next(Agent agent, UUID session) throws Exception {
        return call(post("/api/v1/sessions/" + session + "/next"), agent.token(), null);
    }

    private MvcResult serve(Agent agent, UUID session, Integer version) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/sessions/" + session + "/serve");
        if (version != null) request.header("If-Match", "\"" + version + "\"");
        return call(request, agent.token(), null);
    }

    private MvcResult complete(Agent agent, UUID session, Integer version) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/sessions/" + session + "/complete");
        if (version != null) request.header("If-Match", "\"" + version + "\"");
        return call(request, agent.token(), null);
    }

    private MvcResult current(Agent agent) throws Exception {
        return call(get("/api/v1/sessions/current"), agent.token(), null);
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

    private String stateOf(UUID ticketId) {
        return jdbc.queryForObject("SELECT state FROM ticket WHERE id = ?", String.class, ticketId);
    }

    // ---- FR-QUE-060, FR-ISS-022: issuing ------------------------------------------------------------------------

    @Test
    void journeysAreRefusedUntilTheFeatureFlagIsOn() throws Exception {
        // The flag is a deployment-wide singleton row; another test in this class may have already turned it on.
        jdbc.update("UPDATE journey_settings SET enabled = false WHERE id = 1");
        World w = world();
        Agent reception = user(Role.RECEPTION_OPERATOR, w.site(), w.group());

        MvcResult result = issueJourney(reception, "{\"service_ids\":[\"" + w.a() + "\",\"" + w.b() + "\"],\"ordered\":false}");

        assertThat(status(result)).as(body(result)).isEqualTo(409);
        assertThat((String) field(result, "$.error.details.reason")).isEqualTo("journeys_disabled");
    }

    @Test
    void anUnorderedJourneyIssuesEveryStopUpFrontSharingOneVisitAndMarksTheSoonestOne() throws Exception {
        World w = world();
        Agent admin = user(Role.SYSTEM_ADMIN, w.site(), null);
        enableJourneys(admin);
        Agent reception = user(Role.RECEPTION_OPERATOR, w.site(), w.group());

        MvcResult result = issueJourney(reception, "{\"service_ids\":[\"" + w.a() + "\",\"" + w.b() + "\"],\"ordered\":false}");

        assertThat(status(result)).as(body(result)).isEqualTo(201);
        assertThat((Boolean) field(result, "$.ordered")).isFalse();
        List<String> states = field(result, "$.stops[*].state");
        assertThat(states).containsExactly("waiting", "waiting");
        String visitA = field(result, "$.stops[0].ticket.visit_id");
        String visitB = field(result, "$.stops[1].ticket.visit_id");
        assertThat(visitA).isEqualTo(visitB);
        List<Boolean> soonest = field(result, "$.stops[*].soonest");
        // Each stop is alone in its own Service's queue (position 1); the first one issued wins the tie deterministically.
        assertThat(soonest).as("exactly one stop is marked soonest").containsExactly(true, false);

        Integer stopRows = jdbc.queryForObject("SELECT count(*) FROM journey_stop WHERE visit_id = ?::uuid", Integer.class, visitA);
        assertThat(stopRows).isEqualTo(2);
    }

    @Test
    void anOrderedJourneyIssuesOnlyItsFirstStopUpFront() throws Exception {
        World w = world();
        Agent admin = user(Role.SYSTEM_ADMIN, w.site(), null);
        enableJourneys(admin);
        Agent reception = user(Role.RECEPTION_OPERATOR, w.site(), w.group());

        MvcResult result = issueJourney(reception, "{\"service_ids\":[\"" + w.a() + "\",\"" + w.b() + "\"],\"ordered\":true}");

        assertThat(status(result)).as(body(result)).isEqualTo(201);
        assertThat((Boolean) field(result, "$.ordered")).isTrue();
        List<String> states = field(result, "$.stops[*].state");
        assertThat(states).containsExactly("waiting", "planned");
        String visitId = field(result, "$.stops[0].ticket.visit_id");
        Integer issuedTickets = jdbc.queryForObject("SELECT count(*) FROM journey_stop WHERE visit_id = ?::uuid AND ticket_id IS NOT NULL", Integer.class, visitId);
        assertThat(issuedTickets).isEqualTo(1);
    }

    @Test
    void adHocJourneysRequireAtLeastTwoStopsAndAnExplicitOrderedFlag() throws Exception {
        World w = world();
        Agent admin = user(Role.SYSTEM_ADMIN, w.site(), null);
        enableJourneys(admin);
        Agent reception = user(Role.RECEPTION_OPERATOR, w.site(), w.group());

        MvcResult tooFew = issueJourney(reception, "{\"service_ids\":[\"" + w.a() + "\"],\"ordered\":false}");
        assertThat(status(tooFew)).as(body(tooFew)).isEqualTo(400);

        MvcResult noOrdered = issueJourney(reception, "{\"service_ids\":[\"" + w.a() + "\",\"" + w.b() + "\"]}");
        assertThat(status(noOrdered)).as(body(noOrdered)).isEqualTo(400);
    }

    // ---- FR-QUE-063, Invariant 1: pause and unpause -------------------------------------------------------------

    @Test
    void callingOneStopPausesTheVisitsOtherWaitingStopAndFreesItAgainOnceTheVisitorIsFree() throws Exception {
        World w = world();
        Agent admin = user(Role.SYSTEM_ADMIN, w.site(), null);
        enableJourneys(admin);
        Agent reception = user(Role.RECEPTION_OPERATOR, w.site(), w.group());
        Agent agent = user(Role.AGENT, w.site(), w.group());

        MvcResult issued = issueJourney(reception, "{\"service_ids\":[\"" + w.a() + "\",\"" + w.b() + "\"],\"ordered\":false}");
        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        UUID ticketA = UUID.fromString(field(issued, "$.stops[0].ticket.id"));
        UUID ticketB = UUID.fromString(field(issued, "$.stops[1].ticket.id"));

        UUID session = openSession(agent, w.counter());
        MvcResult called = next(agent, session);
        assertThat(status(called)).as(body(called)).isEqualTo(200);
        UUID calledId = UUID.fromString((String) field(called, "$.ticket.id"));
        UUID otherId = calledId.equals(ticketA) ? ticketB : ticketA;

        assertThat(stateOf(calledId)).isEqualTo("called");
        assertThat(stateOf(otherId)).as("the visit's other stop pauses while one is called (FR-QUE-063)").isEqualTo("paused");
        Integer pausedEvents = jdbc.queryForObject("SELECT count(*) FROM ticket_event WHERE ticket_id = ? AND event_type = 'ticket.paused'", Integer.class, otherId);
        assertThat(pausedEvents).isEqualTo(1);

        // The console shows the visit's other stop and its status (FR-AGT-031).
        MvcResult currentSession = current(agent);
        assertThat(status(currentSession)).as(body(currentSession)).isEqualTo(200);
        List<String> journeyStopStates = field(currentSession, "$.ticket.journey_stops[*].state");
        assertThat(journeyStopStates).containsExactly("paused");

        Integer calledVersion = field(called, "$.ticket.version");
        MvcResult served = serve(agent, session, calledVersion);
        assertThat(status(served)).as(body(served)).isEqualTo(200);
        Integer servingVersion = field(served, "$.ticket.version");

        MvcResult completed = complete(agent, session, servingVersion);
        assertThat(status(completed)).as(body(completed)).isEqualTo(200);

        assertThat(stateOf(otherId)).as("the visitor is free again once the called stop is resolved (FR-QUE-063)").isEqualTo("waiting");
        Integer unpausedEvents = jdbc.queryForObject("SELECT count(*) FROM ticket_event WHERE ticket_id = ? AND event_type = 'ticket.unpaused'", Integer.class, otherId);
        assertThat(unpausedEvents).isEqualTo(1);
    }

    // ---- FR-QUE-061: an ordered Journey continues on completion -----------------------------------------------

    @Test
    void anOrderedJourneyIssuesItsNextStopOnCompletionInheritingThePriorityClass() throws Exception {
        World w = world();
        Agent admin = user(Role.SYSTEM_ADMIN, w.site(), null);
        enableJourneys(admin);
        UUID priorityClass = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO priority_class (id, name_i18n, headstart_minutes, is_default, created_at, updated_at) VALUES (?, '{\"en\":\"Senior\"}'::jsonb, 15, false, now(), now())",
                priorityClass);
        Agent reception = user(Role.RECEPTION_OPERATOR, w.site(), w.group());
        Agent agent = user(Role.AGENT, w.site(), w.group());

        MvcResult issued = issueJourney(
                reception, "{\"service_ids\":[\"" + w.a() + "\",\"" + w.b() + "\"],\"ordered\":true,\"priority_class_id\":\"" + priorityClass + "\"}");
        assertThat(status(issued)).as(body(issued)).isEqualTo(201);
        UUID firstTicket = UUID.fromString(field(issued, "$.stops[0].ticket.id"));
        String visitId = field(issued, "$.stops[0].ticket.visit_id");

        UUID session = openSession(agent, w.counter());
        MvcResult called = next(agent, session);
        assertThat(status(called)).as(body(called)).isEqualTo(200);
        assertThat((String) field(called, "$.ticket.id")).isEqualTo(firstTicket.toString());
        Integer calledVersion = field(called, "$.ticket.version");
        MvcResult served = serve(agent, session, calledVersion);
        assertThat(status(served)).as(body(served)).isEqualTo(200);
        Integer servingVersion = field(served, "$.ticket.version");

        MvcResult completed = complete(agent, session, servingVersion);
        assertThat(status(completed)).as(body(completed)).isEqualTo(200);

        Map<String, Object> secondStop = jdbc.queryForMap(
                "SELECT t.id, t.priority_class_id, t.visit_id FROM journey_stop js JOIN ticket t ON t.id = js.ticket_id WHERE js.visit_id = ?::uuid AND js.seq = 2", visitId);
        assertThat(secondStop.get("visit_id").toString()).isEqualTo(visitId);
        assertThat(secondStop.get("priority_class_id").toString()).as("FR-QUE-061: inherits the visitor's priority class").isEqualTo(priorityClass.toString());
        assertThat(stateOf(UUID.fromString(secondStop.get("id").toString()))).isEqualTo("waiting");
    }
}
