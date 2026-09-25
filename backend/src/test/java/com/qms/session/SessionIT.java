package com.qms.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.qms.issuance.ActorType;
import com.qms.issuance.Channels;
import com.qms.issuance.IssuanceService;
import com.qms.issuance.IssueCommand;
import com.qms.platform.security.Role;
import com.qms.queue.QueueReads;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.dao.DataIntegrityViolationException;
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
 * Tickets 10 and 12 against real PostgreSQL with a clock the test moves: an Agent opens a counter session, calls the highest
 * scoring ticket, starts service and completes it with an outcome, and closes the session. Covers FR-AGT-001, -003, -004,
 * -005, -010, -032, FR-QUE-002, -030, -031, FR-CFG-105, ADR-0008, §18.4, §18.5, §19.3, Invariants 1-3 and NFR-PERF-003; and,
 * from ticket 12, Re-announce and Miss: FR-DSP-028, FR-QUE-050, -051, ADR-0004 and ADR-0005; and, from ticket 16, breaks and
 * availability: FR-AGT-020, -021, -022, -024 and §19.3; and, from ticket 17, the call timeout, the out-of-order call and parallel
 * serving: FR-QUE-032, FR-AGT-010, -011, -012, FR-SEC-040 and ADR-0004.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, SessionIT.Clocks.class})
class SessionIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();
    /** Saturday 19 September 2026, 16:00 in Dhaka (UTC+6). */
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
        // The call timeout check is driven by the tests, on their clock, not by the schedule (FR-QUE-032).
        registry.add("qms.queue.call-timeout-check-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-session");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssuanceService issuance;
    @Autowired QueueReads queues;
    @Autowired MutableClock clock;
    @Autowired SessionService sessionService;

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    /** A site with one zone, one service group with its team and two services ({@code a} and {@code b}). */
    private record World(UUID site, UUID zone, UUID group, UUID a, UUID b) {}

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
        return new World(site, zone, group, newService(group, "A", "Consultation"), newService(group, "B", "Laboratory"));
    }

    private UUID newService(UUID group, String prefix, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode)"
                        + " VALUES (?, ?, ?::jsonb, ?, 10, 30, '[\"reception\",\"kiosk\"]'::jsonb, 'both')",
                id, group, "{\"en\":\"" + name + "\"}", prefix);
        return id;
    }

    private UUID newOutcome(UUID service, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO outcome_code (id, service_id, code, label_i18n) VALUES (?, ?, ?, ?::jsonb)", id, service, code, "{\"en\":\"" + code + "\"}");
        return id;
    }

    /** A counter in the world's zone linked to the given services; each pair is (service, preference weight). */
    private UUID counter(World w, String label, Object... links) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, ?)", id, w.zone(), label);
        for (int i = 0; i < links.length; i += 2) {
            jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, ?)", id, links[i], links[i + 1]);
        }
        return id;
    }

    /** A signed-in staff user; a member of the group's team when {@code teamOf} is given. Tokens are minted at real time. */
    private Agent user(Role role, UUID site, UUID teamOf) throws Exception {
        return user(role, site, teamOf, new UUID[0]);
    }

    /** The same, with the token limited to the given Service groups ({@code groups}; empty means not limited). */
    private Agent user(Role role, UUID site, UUID teamOf, UUID[] groups) throws Exception {
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
                ps.setArray(5, connection.createArrayOf("uuid", groups));
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

    private Agent agent(World w) throws Exception {
        return user(Role.AGENT, w.site(), w.group());
    }

    /** Issues a ticket as if {@code minutesAgo} minutes before BASE, then puts the clock back at BASE. */
    private String issueAgo(UUID service, int minutesAgo) {
        clock.set(BASE.minus(Duration.ofMinutes(minutesAgo)));
        try {
            return issuance.issue(new IssueCommand(service, Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null)).tokenNumber();
        } finally {
            clock.set(BASE);
        }
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andReturn();
    }

    private MvcResult open(Agent agent, UUID counter) throws Exception {
        return call(post("/api/v1/sessions"), agent.token(), "{\"counter_id\":\"" + counter + "\"}");
    }

    private UUID opened(Agent agent, UUID counter) throws Exception {
        MvcResult result = open(agent, counter);
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

    private MvcResult complete(Agent agent, UUID session, UUID outcome, Integer version) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/sessions/" + session + "/complete");
        if (version != null) request.header("If-Match", "\"" + version + "\"");
        return call(request, agent.token(), outcome == null ? null : "{\"outcome_code_id\":\"" + outcome + "\",\"note\":\"done\"}");
    }

    private MvcResult reannounce(Agent agent, UUID session, Integer version) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/sessions/" + session + "/reannounce");
        if (version != null) request.header("If-Match", "\"" + version + "\"");
        return call(request, agent.token(), null);
    }

    private MvcResult miss(Agent agent, UUID session, Integer version) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/sessions/" + session + "/miss");
        if (version != null) request.header("If-Match", "\"" + version + "\"");
        return call(request, agent.token(), null);
    }

    /** F8: holds the serving ticket, or, given {@code resume}, resumes that held ticket. */
    private MvcResult hold(Agent agent, UUID session, UUID resume, Integer version) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/sessions/" + session + "/hold");
        if (version != null) request.header("If-Match", "\"" + version + "\"");
        return call(request, agent.token(), resume == null ? null : "{\"ticket_id\":\"" + resume + "\"}");
    }

    private MvcResult forceClose(Agent admin, UUID session, String reason) throws Exception {
        return call(post("/api/v1/sessions/" + session + "/force-close"), admin.token(), reason == null ? null : "{\"reason\":\"" + reason + "\"}");
    }

    /** Calls the next ticket and starts service on it; returns its token number. */
    private String servingToken(Agent agent, UUID session) throws Exception {
        String token = calledToken(agent, session);
        MvcResult started = serve(agent, session, null);
        assertThat(status(started)).as(body(started)).isEqualTo(200);
        return token;
    }

    /** The token numbers of a service's waiting tickets in the order they will be called. */
    private List<String> queueOrder(UUID service) {
        return queues.ordered(service, null).entries().stream().map(QueueReads.Entry::tokenNumber).toList();
    }

    private List<Map<String, Object>> eventsOf(Map<String, Object> ticket) {
        return jdbc.queryForList("SELECT seq, event_type, from_state, to_state, counter_id, payload::text AS payload FROM ticket_event WHERE ticket_id = ? ORDER BY seq", ticket.get("id"));
    }

    private static Object inPayload(Map<String, Object> event, String path) {
        return JsonPath.read((String) event.get("payload"), path);
    }

    private MvcResult close(Agent agent, UUID session) throws Exception {
        return call(delete("/api/v1/sessions/" + session), agent.token(), null);
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

    private static String reason(MvcResult result) throws Exception {
        return field(result, "$.error.details.reason");
    }

    /** Calls next and returns the token number called, failing the test if the call is refused. */
    private String calledToken(Agent agent, UUID session) throws Exception {
        MvcResult called = next(agent, session);
        assertThat(status(called)).as(body(called)).isEqualTo(200);
        return field(called, "$.ticket.token_number");
    }

    private Map<String, Object> ticketRow(String token, UUID service) {
        return jdbc.queryForMap("SELECT * FROM ticket WHERE token_number = ? AND service_id = ?", token, service);
    }

    // ---- FR-AGT-001: opening a session on a permitted counter ---------------------------------------------------

    @Test
    void anAgentOpensASessionOnACounterTheirTeamServesAndIsToldWhichCountersThatIs() throws Exception {
        World w = world();
        UUID desk1 = counter(w, "Desk 1", w.a(), 1, w.b(), 2);
        UUID elsewhere = counter(w, "Desk 2", newService(otherGroup(w), "Z", "Other"), 1);
        Agent agent = agent(w);

        MvcResult options = call(get("/api/v1/sessions/options"), agent.token(), null);
        assertThat(status(options)).as(body(options)).isEqualTo(200);
        assertThat((List<String>) field(options, "$.items[*].counter.label")).as("only the counters their team serves").containsExactly("Desk 1");
        assertThat((List<Integer>) field(options, "$.items[0].services[*].preference_weight")).containsExactly(1, 2);
        assertThat((Boolean) field(options, "$.items[0].occupied")).isFalse();

        MvcResult opened = open(agent, desk1);

        assertThat(status(opened)).as(body(opened)).isEqualTo(201);
        assertThat((String) field(opened, "$.state")).isEqualTo("open");
        assertThat((String) field(opened, "$.agent_id")).isEqualTo(agent.id().toString());
        assertThat((String) field(opened, "$.counter.label")).isEqualTo("Desk 1");
        assertThat((Object) field(opened, "$.ticket")).isNull();
        assertThat(status(open(agent(w), elsewhere))).as("a counter their team does not serve").isEqualTo(403);
    }

    private UUID otherGroup(World w) {
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Radiology\"}'::jsonb, 'R')", group, w.site());
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, 'Radiology team')", UUID.randomUUID(), group);
        return group;
    }

    @Test
    void onlyAnAgentWhoseTeamServesTheCounterMayOccupyItAndUnknownOrSwitchedOffCountersAreRefused() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        Agent outsider = user(Role.AGENT, w.site(), null);
        Agent reception = user(Role.RECEPTION_OPERATOR, w.site(), w.group());

        assertThat(status(open(outsider, desk))).as("not on the team").isEqualTo(403);
        assertThat(status(open(reception, desk))).as("reception has no session permission").isEqualTo(403);
        assertThat(status(call(post("/api/v1/sessions"), null, "{\"counter_id\":\"" + desk + "\"}"))).as("unauthenticated").isEqualTo(401);
        assertThat(status(open(agent(w), UUID.randomUUID()))).as("no such counter").isEqualTo(404);
        assertThat(status(call(post("/api/v1/sessions"), agent(w).token(), "{}"))).isEqualTo(400);

        jdbc.update("UPDATE counter SET active = false WHERE id = ?", desk);
        MvcResult inactive = open(agent(w), desk);
        assertThat(status(inactive)).isEqualTo(409);
        assertThat(reason(inactive)).isEqualTo("counter_inactive");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM counter_session WHERE counter_id = ?", Integer.class, desk)).isZero();
    }

    @Test
    void anAgentOutsideTheCountersSiteScopeCannotOccupyIt() throws Exception {
        World w = world();
        World elsewhere = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        // A member of w's team whose token is limited to another site.
        Agent scoped = user(Role.AGENT, elsewhere.site(), w.group());

        assertThat(status(open(scoped, desk))).isEqualTo(403);
        assertThat((List<Object>) field(call(get("/api/v1/sessions/options"), scoped.token(), null), "$.items")).isEmpty();
    }

    // ---- §18.4: one open session per counter, enforced by the database -----------------------------------------

    @Test
    void aCounterHoldsOneOpenSessionAndTheDatabaseItselfRefusesASecond() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        Agent first = agent(w);
        Agent second = agent(w);
        UUID session = opened(first, desk);

        MvcResult refused = open(second, desk);
        assertThat(status(refused)).isEqualTo(409);
        assertThat(reason(refused)).isEqualTo("counter_occupied");
        assertThat((Boolean) field(call(get("/api/v1/sessions/options"), second.token(), null), "$.items[0].occupied")).isTrue();

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO counter_session (id, counter_id, agent_id, opened_at, services, state) VALUES (?, ?, ?, now(), '{}', 'open')",
                        UUID.randomUUID(), desk, second.id()))
                .as("a second open row for the counter, bypassing the code")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(status(close(first, session))).isEqualTo(200);
        assertThat(status(open(second, desk))).as("a closed session frees the counter").isEqualTo(201);
    }

    @Test
    void anAgentSitsAtOneCounterAtATime() throws Exception {
        World w = world();
        UUID desk1 = counter(w, "Desk 1", w.a(), 1);
        UUID desk2 = counter(w, "Desk 2", w.a(), 1);
        Agent agent = agent(w);
        opened(agent, desk1);

        MvcResult refused = open(agent, desk2);

        assertThat(status(refused)).isEqualTo(409);
        assertThat(reason(refused)).isEqualTo("agent_has_open_session");
    }

    @Test
    void concurrentAttemptsToOpenTheSameCounterLeaveExactlyOneSession() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        List<Agent> agents = new ArrayList<>();
        for (int i = 0; i < 6; i++) agents.add(agent(w));

        List<Integer> statuses = inParallel(agents.stream().<Callable<Integer>>map(a -> () -> status(open(a, desk))).toList());

        assertThat(Collections.frequency(statuses, 201)).isEqualTo(1);
        assertThat(Collections.frequency(statuses, 409)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM counter_session WHERE counter_id = ? AND state = 'open'", Integer.class, desk)).isEqualTo(1);
    }

    // ---- FR-AGT-003: which of the counter's services to serve --------------------------------------------------

    @Test
    void anAgentServesAllTheCountersServicesByDefaultOrChoosesSome() throws Exception {
        World w = world();
        UUID desk1 = counter(w, "Desk 1", w.a(), 1, w.b(), 2);
        UUID desk2 = counter(w, "Desk 2", w.a(), 1, w.b(), 2);
        issueAgo(w.a(), 10);
        String labTicket = issueAgo(w.b(), 30);

        Agent everything = agent(w);
        opened(everything, desk1);
        assertThat((List<String>) field(current(everything), "$.services[*].name_i18n.en")).as("all of them by default").containsExactly("Consultation", "Laboratory");

        Agent picky = agent(w);
        MvcResult chosen = call(post("/api/v1/sessions"), picky.token(), "{\"counter_id\":\"" + desk2 + "\",\"service_ids\":[\"" + w.a() + "\"]}");
        assertThat(status(chosen)).as(body(chosen)).isEqualTo(201);
        assertThat((List<String>) field(chosen, "$.services[*].name_i18n.en")).containsExactly("Consultation");
        UUID session = UUID.fromString(field(chosen, "$.id"));
        assertThat(calledToken(picky, session)).as("the older laboratory ticket is not this session's to call").isNotEqualTo(labTicket);
        assertThat(ticketRow(labTicket, w.b()).get("state")).isEqualTo("waiting");
    }

    @Test
    void servicesThatAreNotTheCountersOrAreEmptyAreRefused() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        Agent agent = agent(w);
        for (String services : List.of("[]", "[\"" + w.b() + "\"]", "[\"" + UUID.randomUUID() + "\"]", "[\"" + w.a() + "\",null]")) {
            MvcResult refused = call(post("/api/v1/sessions"), agent.token(), "{\"counter_id\":\"" + desk + "\",\"service_ids\":" + services + "}");
            assertThat(status(refused)).as(services).isEqualTo(400);
            assertThat((String) field(refused, "$.error.details.fields[0].field")).isEqualTo("service_ids");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM counter_session WHERE counter_id = ?", Integer.class, desk)).isZero();
    }

    // ---- FR-QUE-002, FR-QUE-030: call next across the counter's queues -----------------------------------------

    @Test
    void callNextTakesTheHighestScoringTicketAcrossEveryQueueTheCounterServes() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1, w.b(), 1);
        issueAgo(w.a(), 8);
        issueAgo(w.a(), 5);
        String longest = issueAgo(w.b(), 25);
        issueAgo(w.b(), 3);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);

        MvcResult called = next(agent, session);

        assertThat(status(called)).as(body(called)).isEqualTo(200);
        assertThat((String) field(called, "$.ticket.token_number")).isEqualTo(longest);
        assertThat((String) field(called, "$.ticket.state")).isEqualTo("called");
        assertThat((String) field(called, "$.ticket.service.name_i18n.en")).isEqualTo("Laboratory");
        assertThat((Integer) field(called, "$.ticket.wait_seconds")).isEqualTo(25 * 60);
        assertThat((String) field(called, "$.ticket.origin_channel")).isEqualTo("reception");
    }

    @Test
    void aPrimaryLinkIsPreferredWhileItsTicketTrailsTheBestByNoMoreThanTheTolerance() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1, w.b(), 2);
        String primary = issueAgo(w.a(), 20);
        issueAgo(w.b(), 24); // 4 minutes better, inside the 5-minute tolerance: the fallback does not win
        Agent agent = agent(w);
        UUID session = opened(agent, desk);

        assertThat(calledToken(agent, session)).isEqualTo(primary);
    }

    @Test
    void aFallbackLinkWinsOnceItsTicketIsBetterByMoreThanTheTolerance() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1, w.b(), 2);
        issueAgo(w.a(), 20);
        String fallback = issueAgo(w.b(), 27); // 7 minutes better, outside the tolerance
        Agent agent = agent(w);
        UUID session = opened(agent, desk);

        assertThat(calledToken(agent, session)).isEqualTo(fallback);
    }

    @Test
    void aPausedTicketKeepsItsPlaceButIsNeverCalledAndAnEmptyQueueIsSaidSo() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String paused = issueAgo(w.a(), 40);
        String waiting = issueAgo(w.a(), 5);
        jdbc.update("UPDATE ticket SET state = 'paused' WHERE token_number = ? AND service_id = ?", paused, w.a());
        Agent agent = agent(w);
        UUID session = opened(agent, desk);

        assertThat(calledToken(agent, session)).isEqualTo(waiting);
        assertThat(status(serve(agent, session, null))).isEqualTo(200);
        assertThat(status(complete(agent, session, null, null))).isEqualTo(200);
        MvcResult empty = next(agent, session);
        assertThat(status(empty)).isEqualTo(409);
        assertThat(reason(empty)).isEqualTo("no_ticket_waiting");
        assertThat(ticketRow(paused, w.a()).get("state")).isEqualTo("paused");
    }

    // ---- FR-QUE-031, ADR-0008, Invariant 2: no two counters call the same ticket -------------------------------

    @Test
    void ticketsCalledByManyCountersAtOnceAreEachCalledExactlyOnce() throws Exception {
        World w = world();
        List<Agent> agents = new ArrayList<>();
        List<UUID> sessions = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Agent agent = agent(w);
            agents.add(agent);
            sessions.add(opened(agent, counter(w, "Desk " + i, w.a(), 1, w.b(), 2)));
        }
        List<String> issued = new ArrayList<>();
        for (int i = 0; i < 40; i++) issued.add(issueAgo(i % 2 == 0 ? w.a() : w.b(), 60 - i));

        // Every desk keeps calling, serving and completing until nothing is left, all at the same time.
        List<Callable<List<String>>> desks = new ArrayList<>();
        for (int i = 0; i < agents.size(); i++) {
            Agent agent = agents.get(i);
            UUID session = sessions.get(i);
            desks.add(() -> {
                List<String> mine = new ArrayList<>();
                while (true) {
                    MvcResult called = next(agent, session);
                    if (status(called) == 409) {
                        assertThat(reason(called)).isEqualTo("no_ticket_waiting");
                        return mine;
                    }
                    assertThat(status(called)).as(body(called)).isEqualTo(200);
                    mine.add(field(called, "$.ticket.service.id") + "|" + field(called, "$.ticket.token_number"));
                    assertThat(status(serve(agent, session, null))).isEqualTo(200);
                    assertThat(status(complete(agent, session, null, null))).isEqualTo(200);
                }
            });
        }
        List<List<String>> perDesk = inParallel(desks);

        List<String> all = perDesk.stream().flatMap(List::stream).toList();
        assertThat(all).as("every ticket was called, and none twice").hasSize(40).doesNotHaveDuplicates();
        assertThat(all.stream().map(s -> s.substring(s.indexOf('|') + 1)).toList()).containsExactlyInAnyOrderElementsOf(issued);
        for (UUID service : List.of(w.a(), w.b())) {
            assertThat(jdbc.queryForList("SELECT e.ticket_id FROM ticket_event e JOIN ticket t ON t.id = e.ticket_id WHERE t.service_id = ? AND e.event_type = 'ticket.called' GROUP BY e.ticket_id HAVING count(*) > 1", UUID.class, service))
                    .as("no ticket has two call events").isEmpty();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE service_id IN (?, ?) AND state = 'completed' AND counter_session_id IS NULL", Integer.class, w.a(), w.b())).isEqualTo(40);
    }

    @Test
    void twoCountersCallingTheSingleWaitingTicketAtOnceGiveItToOneAndTheOtherIsToldNothingIsWaiting() throws Exception {
        World w = world();
        Agent one = agent(w);
        Agent two = agent(w);
        UUID s1 = opened(one, counter(w, "Desk 1", w.a(), 1));
        UUID s2 = opened(two, counter(w, "Desk 2", w.a(), 1));
        String only = issueAgo(w.a(), 10);

        List<MvcResult> results = inParallel(List.of(() -> next(one, s1), () -> next(two, s2)));

        assertThat(results.stream().map(SessionIT::status)).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE token_number = ? AND service_id = ? AND counter_session_id IS NOT NULL", Integer.class, only, w.a())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_event WHERE event_type = 'ticket.called' AND ticket_id = (SELECT id FROM ticket WHERE token_number = ? AND service_id = ?)", Integer.class, only, w.a())).isEqualTo(1);
    }

    // ---- FR-AGT-010: call next is disabled while a ticket is called or serving ---------------------------------

    @Test
    void callNextIsRefusedWhileATicketIsCalledOrServingAndAllowedAgainOnceItIsCompleted() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        issueAgo(w.a(), 20);
        String second = issueAgo(w.a(), 10);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        calledToken(agent, session);

        MvcResult whileCalled = next(agent, session);
        assertThat(status(whileCalled)).isEqualTo(409);
        assertThat(reason(whileCalled)).isEqualTo("ticket_in_progress");

        assertThat(status(serve(agent, session, null))).isEqualTo(200);
        MvcResult whileServing = next(agent, session);
        assertThat(status(whileServing)).isEqualTo(409);
        assertThat(reason(whileServing)).isEqualTo("ticket_in_progress");

        assertThat(status(complete(agent, session, null, null))).isEqualTo(200);
        assertThat(calledToken(agent, session)).isEqualTo(second);
    }

    // ---- FR-AGT-032, §19.1: start service and complete with an outcome -----------------------------------------

    @Test
    void anAgentStartsServiceAndCompletesWithAnOutcomeFromTheServicesListAndEachStepBumpsTheVersion() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        UUID resolved = newOutcome(w.a(), "resolved");
        String token = issueAgo(w.a(), 12);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);

        MvcResult called = next(agent, session);
        assertThat((Integer) field(called, "$.ticket.version")).as("issue = 0, call = 1").isEqualTo(1);
        assertThat((List<String>) field(called, "$.ticket.outcomes[*].code")).containsExactly("resolved");
        UUID ticketId = UUID.fromString(field(called, "$.ticket.id"));
        MvcResult asVisitorSees = call(get("/api/v1/tickets/" + ticketId), agent.token(), null);
        assertThat((String) field(asVisitorSees, "$.state")).isEqualTo("called");
        assertThat((Object) field(asVisitorSees, "$.position")).as("a called ticket has left the queue").isNull();

        MvcResult served = serve(agent, session, 1);
        assertThat(status(served)).as(body(served)).isEqualTo(200);
        assertThat((String) field(served, "$.ticket.state")).isEqualTo("serving");
        assertThat((Integer) field(served, "$.ticket.version")).isEqualTo(2);

        MvcResult done = complete(agent, session, resolved, 2);
        assertThat(status(done)).as(body(done)).isEqualTo(200);
        assertThat((Object) field(done, "$.ticket")).as("nothing in progress any more").isNull();

        Map<String, Object> row = ticketRow(token, w.a());
        assertThat(row.get("state")).isEqualTo("completed");
        assertThat(row.get("version")).isEqualTo(3);
        assertThat(row.get("outcome_code_id")).isEqualTo(resolved);
        assertThat(row.get("note")).isEqualTo("done");
        assertThat(row.get("counter_session_id")).as("the binding is cleared on a terminal state").isNull();
        assertThat(row.get("counter_id")).isEqualTo(desk);
        assertThat(row.get("agent_id")).isEqualTo(agent.id());
        assertThat(row.get("called_at")).isNotNull();
        assertThat(row.get("served_at")).isNotNull();
        assertThat(row.get("closed_at")).isNotNull();
    }

    @Test
    void theOutcomeMustBelongToTheTicketsServiceAndBeActiveAndIsRequiredOnlyWhereTheServiceHasOutcomes() throws Exception {
        World w = world();
        UUID plain = newService(w.group(), "C", "Plain");
        UUID desk = counter(w, "Desk 1", w.a(), 1, w.b(), 1, plain, 1);
        UUID mine = newOutcome(w.a(), "resolved");
        UUID other = newOutcome(w.b(), "referred");
        UUID retired = newOutcome(w.a(), "old");
        jdbc.update("UPDATE outcome_code SET active = false WHERE id = ?", retired);
        issueAgo(w.a(), 20);
        issueAgo(plain, 10);
        issueAgo(w.b(), 5);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        calledToken(agent, session);
        serve(agent, session, null);

        MvcResult missing = complete(agent, session, null, null);
        assertThat(status(missing)).isEqualTo(400);
        assertThat((String) field(missing, "$.error.details.fields[0].field")).isEqualTo("outcome_code_id");
        assertThat((String) field(missing, "$.error.details.fields[0].code")).isEqualTo("required");
        assertThat(status(complete(agent, session, other, null))).as("another service's outcome").isEqualTo(400);
        assertThat(status(complete(agent, session, retired, null))).as("a deactivated outcome").isEqualTo(400);
        assertThat(status(complete(agent, session, UUID.randomUUID(), null))).as("an unknown outcome").isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE service_id = ? AND state = 'serving'", Integer.class, w.a())).as("nothing was completed").isEqualTo(1);

        assertThat(status(complete(agent, session, mine, null))).isEqualTo(200);

        // The next ticket is for a service with no outcome codes at all, so a completion without one is fine.
        MvcResult nextCalled = next(agent, session);
        assertThat((String) field(nextCalled, "$.ticket.service.name_i18n.en")).isEqualTo("Plain");
        serve(agent, session, null);
        assertThat(status(complete(agent, session, null, null))).isEqualTo(200);
    }

    @Test
    void anActionOnAStaleTicketVersionIsRefusedAndTheTicketIsLeftAsItWas() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String token = issueAgo(w.a(), 12);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        calledToken(agent, session);

        MvcResult stale = serve(agent, session, 0);
        assertThat(status(stale)).isEqualTo(409);
        assertThat(reason(stale)).isEqualTo("version_mismatch");
        assertThat(ticketRow(token, w.a()).get("state")).isEqualTo("called");

        assertThat(status(call(post("/api/v1/sessions/" + session + "/serve").header("If-Match", "banana"), agent.token(), null))).isEqualTo(400);
        assertThat(status(serve(agent, session, 1))).isEqualTo(200);
        MvcResult staleComplete = complete(agent, session, null, 1);
        assertThat(reason(staleComplete)).isEqualTo("version_mismatch");
        assertThat(status(complete(agent, session, null, 2))).isEqualTo(200);
    }

    @Test
    void servingOrCompletingNeedsATicketInTheRightState() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        issueAgo(w.a(), 12);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);

        assertThat(reason(serve(agent, session, null))).as("nothing has been called").isEqualTo("no_ticket_called");
        assertThat(reason(complete(agent, session, null, null))).isEqualTo("no_ticket_serving");
        calledToken(agent, session);
        assertThat(reason(complete(agent, session, null, null))).as("a called ticket must be started first").isEqualTo("no_ticket_serving");
        assertThat(status(serve(agent, session, null))).isEqualTo(200);
        assertThat(reason(serve(agent, session, null))).as("already serving").isEqualTo("no_ticket_called");
    }

    // ---- §18.5, Invariant 1: wait_seconds and service_seconds stored at closure --------------------------------

    @Test
    void waitAndServiceSecondsAreStoredAtClosureAndWaitStopsAccruingOnceTheTicketIsCalled() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String token = issueAgo(w.a(), 5); // queued at BASE - 5 min
        Agent agent = agent(w);
        UUID session = opened(agent, desk);

        clock.set(BASE); // called after waiting 300 s
        calledToken(agent, session);
        assertThat(ticketRow(token, w.a()).get("wait_seconds")).as("stored at closure, not before").isNull();
        clock.set(BASE.plusSeconds(600)); // the visitor takes 10 minutes to reach the desk: not wait, not service
        serve(agent, session, null);
        clock.set(BASE.plusSeconds(600 + 1500)); // 25 minutes of service
        complete(agent, session, null, null);

        Map<String, Object> row = ticketRow(token, w.a());
        assertThat(row.get("wait_seconds")).isEqualTo(300);
        assertThat(row.get("service_seconds")).isEqualTo(1500);
        List<String> payload = jdbc.queryForList("SELECT payload::text FROM ticket_event WHERE ticket_id = ? AND event_type = 'ticket.completed'", String.class, row.get("id"));
        assertThat(payload).singleElement().asString().contains("\"wait_seconds\": 300").contains("\"service_seconds\": 1500");
    }

    // ---- Invariant 3, FR-QUE-070: every transition writes exactly one event; audit entries ---------------------

    @Test
    void everyTransitionWritesOneEventInSequenceAndOpeningAndClosingAreAudited() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String token = issueAgo(w.a(), 12);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        calledToken(agent, session);
        serve(agent, session, null);
        complete(agent, session, null, null);
        clock.advance(Duration.ofMinutes(1));
        close(agent, session);

        UUID ticketId = (UUID) ticketRow(token, w.a()).get("id");
        List<Map<String, Object>> events = jdbc.queryForList("SELECT seq, event_type, from_state, to_state, actor_id, actor_type, counter_id FROM ticket_event WHERE ticket_id = ? ORDER BY seq", ticketId);
        assertThat(events.stream().map(e -> e.get("event_type"))).containsExactly("ticket.issued", "ticket.called", "ticket.serving", "ticket.completed");
        assertThat(events.stream().map(e -> e.get("seq"))).containsExactly(1, 2, 3, 4);
        assertThat(events.stream().map(e -> e.get("from_state") + ">" + e.get("to_state"))).containsExactly("null>waiting", "waiting>called", "called>serving", "serving>completed");
        assertThat(events.get(1).get("actor_id")).isEqualTo(agent.id());
        assertThat(events.get(1).get("actor_type")).isEqualTo("staff");
        assertThat(events.get(1).get("counter_id")).isEqualTo(desk);
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE entity = 'counter_session' AND entity_id = ? ORDER BY occurred_at, id", String.class, session))
                .containsExactly("session.opened", "session.closed");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE action = 'session.opened' AND entity_id = ?", UUID.class, session)).isEqualTo(agent.id());
    }

    // ---- FR-CFG-105, FR-CFG-103: own record via the session binding, permissions on the server -----------------

    @Test
    void anAgentActsOnlyOnTheirOwnSessionAndNobodyElsesEvenWithTheSamePermission() throws Exception {
        World w = world();
        UUID desk1 = counter(w, "Desk 1", w.a(), 1);
        UUID desk2 = counter(w, "Desk 2", w.a(), 1);
        issueAgo(w.a(), 20);
        issueAgo(w.a(), 10);
        Agent owner = agent(w);
        Agent other = agent(w);
        UUID ownersSession = opened(owner, desk1);
        opened(other, desk2);
        String called = calledToken(owner, ownersSession);

        assertThat(status(next(other, ownersSession))).as("call on another's session").isEqualTo(403);
        assertThat(status(serve(other, ownersSession, null))).as("serve another's ticket").isEqualTo(403);
        assertThat(status(complete(other, ownersSession, null, null))).as("complete another's ticket").isEqualTo(403);
        assertThat(status(close(other, ownersSession))).as("close another's session").isEqualTo(403);
        assertThat(status(next(owner, UUID.randomUUID()))).as("unknown session").isEqualTo(404);
        assertThat(ticketRow(called, w.a()).get("state")).as("nothing changed").isEqualTo("called");
        assertThat(status(serve(owner, ownersSession, null))).isEqualTo(200);
    }

    @Test
    void everySessionActionChecksThePermissionOnTheServer() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        String path = "/api/v1/sessions/" + session;
        // Org Admin may open and close sessions (§5.2) but not call, serve or complete; Reception may do none of it.
        Agent admin = user(Role.ORG_ADMIN, w.site(), w.group());
        Agent reception = user(Role.RECEPTION_OPERATOR, w.site(), w.group());
        for (Agent denied : List.of(admin, reception)) {
            assertThat(status(call(post(path + "/next"), denied.token(), null))).as("next").isEqualTo(403);
            assertThat(status(call(post(path + "/serve"), denied.token(), null))).as("serve").isEqualTo(403);
            assertThat(status(call(post(path + "/complete"), denied.token(), null))).as("complete").isEqualTo(403);
        }
        assertThat(status(call(get("/api/v1/sessions/options"), reception.token(), null))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/sessions/current"), reception.token(), null))).isEqualTo(403);
        assertThat(status(call(delete(path), reception.token(), null))).isEqualTo(403);
        for (String action : List.of("/next", "/serve", "/complete")) {
            assertThat(status(call(post(path + action), null, null))).as("unauthenticated " + action).isEqualTo(401);
        }
        assertThat(status(call(get("/api/v1/sessions/current"), null, null))).isEqualTo(401);
        assertThat(status(call(delete(path), null, null))).isEqualTo(401);
    }

    // ---- FR-AGT-004: the session survives a refresh, a network loss and a restart -------------------------------

    @Test
    void theSessionAndItsTicketInProgressAreRestoredAfterARefreshAHoursLongGapOrARestart() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        UUID outcome = newOutcome(w.a(), "resolved");
        issueAgo(w.a(), 12);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        calledToken(agent, session);
        serve(agent, session, null);

        MvcResult refreshed = current(agent);
        assertThat(status(refreshed)).isEqualTo(200);
        assertThat((String) field(refreshed, "$.id")).isEqualTo(session.toString());
        assertThat((String) field(refreshed, "$.ticket.state")).isEqualTo("serving");
        assertThat((Integer) field(refreshed, "$.ticket.version")).isEqualTo(2);

        clock.advance(Duration.ofMinutes(4)); // a short network loss
        assertThat((String) field(current(agent), "$.ticket.state")).isEqualTo("serving");
        clock.advance(Duration.ofHours(15)); // the device was off overnight: nothing expires the session
        MvcResult restarted = current(agent(w, agent));
        assertThat((String) field(restarted, "$.id")).isEqualTo(session.toString());
        assertThat((String) field(restarted, "$.ticket.token_number")).isNotNull();
        assertThat((List<String>) field(restarted, "$.ticket.outcomes[*].code")).containsExactly("resolved");
        assertThat(status(complete(agent, session, outcome, 2))).as("and the work carries on where it stopped").isEqualTo(200);
    }

    /** The same agent signing in again, as after a device restart. */
    private Agent agent(World w, Agent again) throws Exception {
        String username = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, again.id());
        Instant testTime = clock.instant();
        clock.set(Instant.now());
        try {
            MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
            assertThat(status(result)).isEqualTo(200);
            return new Agent(again.id(), JsonPath.read(body(result), "$.access_token"));
        } finally {
            clock.set(testTime);
        }
    }

    @Test
    void anAgentWithNoLiveSessionIsToldThereIsNone() throws Exception {
        World w = world();
        Agent agent = agent(w);
        MvcResult none = current(agent);
        assertThat(status(none)).isEqualTo(404);
        assertThat((String) field(none, "$.error.code")).isEqualTo("not_found");
    }

    // ---- FR-AGT-005, §19.3: closing needs the in-progress ticket resolved ---------------------------------------

    @Test
    void closingWithATicketInProgressIsRefusedTakesNoNewCallsAndClosesOnceTheTicketIsCompleted() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        issueAgo(w.a(), 20);
        issueAgo(w.a(), 10);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        String held = calledToken(agent, session);

        MvcResult refused = close(agent, session);
        assertThat(status(refused)).isEqualTo(409);
        assertThat(reason(refused)).isEqualTo("ticket_in_progress");
        assertThat(jdbc.queryForObject("SELECT state FROM counter_session WHERE id = ?", String.class, session)).as("closing: no new assignments").isEqualTo("closing");
        assertThat(reason(next(agent, session))).isEqualTo("session_not_open");
        assertThat(ticketRow(held, w.a()).get("state")).as("the ticket was not dropped").isEqualTo("called");
        assertThat(status(open(agent, counter(w, "Desk 2", w.a(), 1)))).as("still occupying its counter").isEqualTo(409);

        assertThat(status(serve(agent, session, null))).isEqualTo(200);
        MvcResult done = complete(agent, session, null, null);
        assertThat(status(done)).isEqualTo(200);
        assertThat((String) field(done, "$.state")).as("closing → closed once every ticket is resolved").isEqualTo("closed");
        assertThat(jdbc.queryForObject("SELECT closed_at FROM counter_session WHERE id = ?", Object.class, session)).isNotNull();
        assertThat(status(current(agent))).isEqualTo(404);
        assertThat(reason(next(agent, session))).as("a closed session takes nothing").isEqualTo("session_not_open");
    }

    @Test
    void aSessionWithNothingInProgressClosesAtOnceAndClosingAgainIsHarmless() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);

        MvcResult closed = close(agent, session);

        assertThat(status(closed)).isEqualTo(200);
        assertThat((String) field(closed, "$.state")).isEqualTo("closed");
        assertThat((String) field(closed, "$.closed_at")).isNotNull();
        assertThat(status(close(agent, session))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'session.closed' AND entity_id = ?", Integer.class, session)).as("audited once").isEqualTo(1);
    }

    // ---- FR-DSP-028, ADR-0005: Re-announce (F3) ---------------------------------------------------------------

    @Test
    void reannouncingKeepsTheTicketCalledAndBoundCountsEachRepeatAndStopsAtTheLimit() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String token = issueAgo(w.a(), 10);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        calledToken(agent, session);

        for (int repeat = 1; repeat <= 3; repeat++) {
            MvcResult again = reannounce(agent, session, repeat); // the ticket is at version 1 after the call, then one more per repeat
            assertThat(status(again)).as(body(again)).isEqualTo(200);
            assertThat((String) field(again, "$.ticket.state")).isEqualTo("called");
            assertThat((Integer) field(again, "$.ticket.announce_count")).isEqualTo(repeat);
            assertThat((Integer) field(again, "$.ticket.announce_limit")).isEqualTo(3);
            assertThat((Integer) field(again, "$.ticket.version")).isEqualTo(repeat + 1);
            Map<String, Object> row = ticketRow(token, w.a());
            assertThat(row.get("state")).isEqualTo("called");
            assertThat(row.get("counter_session_id")).as("the Session binding is kept").isEqualTo(session);
            assertThat(row.get("announce_count")).isEqualTo(repeat);
            assertThat(row.get("miss_count")).as("a Re-announce is not a Miss").isEqualTo(0);
        }

        MvcResult capped = reannounce(agent, session, null);
        assertThat(status(capped)).isEqualTo(409);
        assertThat(reason(capped)).isEqualTo("reannounce_limit_reached");
        Map<String, Object> row = ticketRow(token, w.a());
        assertThat(row.get("announce_count")).isEqualTo(3);
        assertThat(row.get("state")).isEqualTo("called");

        List<Map<String, Object>> events = eventsOf(row);
        assertThat(events.stream().map(e -> e.get("event_type"))).containsExactly("ticket.issued", "ticket.called", "ticket.reannounced", "ticket.reannounced", "ticket.reannounced");
        assertThat(events.get(2).get("from_state")).isEqualTo("called");
        assertThat(events.get(2).get("to_state")).isEqualTo("called");
        assertThat(events.get(2).get("counter_id")).isEqualTo(desk);
        assertThat(events.stream().skip(2).map(e -> inPayload(e, "$.announce_count"))).containsExactly(1, 2, 3);
        assertThat(status(serve(agent, session, null))).as("the ticket is still the session's to serve").isEqualTo(200);
    }

    // ---- FR-QUE-050, FR-QUE-051, ADR-0004, ADR-0005: Miss (F6) ------------------------------------------------

    @Test
    void aMissReturnsTheTicketToWaitingAfterThreeOthersFreesTheCounterAndKeepsItsOriginalWait() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String missed = issueAgo(w.a(), 60);
        List<String> others = new ArrayList<>();
        for (int ago : new int[] {50, 40, 30, 20, 10}) others.add(issueAgo(w.a(), ago));
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        assertThat(calledToken(agent, session)).as("the longest waiting is called first").isEqualTo(missed);
        Object queuedAt = ticketRow(missed, w.a()).get("queued_at");

        MvcResult result = miss(agent, session, 1);

        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat((Object) field(result, "$.ticket")).as("the counter is free").isNull();
        Map<String, Object> row = ticketRow(missed, w.a());
        assertThat(row.get("state")).isEqualTo("waiting");
        assertThat(row.get("counter_session_id")).as("the binding is cleared on the return to waiting (Invariant 2)").isNull();
        assertThat(row.get("miss_count")).isEqualTo(1);
        assertThat(row.get("queued_at")).as("queued_at is never rewritten (ADR-0004)").isEqualTo(queuedAt);
        assertThat(row.get("wait_seconds")).as("stored at closure only").isNull();
        assertThat(row.get("closed_at")).isNull();
        // Its score was 60; the third ticket in line scores 30, so it lands just behind it: 30 - 1 = 29 = 60 - 31.
        assertThat(row.get("score_adjustment_minutes")).isEqualTo(-31);
        assertThat(queueOrder(w.a())).containsExactly(others.get(0), others.get(1), others.get(2), missed, others.get(3), others.get(4));

        Map<String, Object> event = eventsOf(row).getLast();
        assertThat(event.get("event_type")).isEqualTo("ticket.missed");
        assertThat(event.get("from_state") + ">" + event.get("to_state")).isEqualTo("called>waiting");
        assertThat(event.get("counter_id")).isEqualTo(desk);
        assertThat(inPayload(event, "$.score_adjustment_minutes")).as("each positional move writes the adjustment applied").isEqualTo(-31);
        assertThat(inPayload(event, "$.reentry_position")).isEqualTo("after_n");
        assertThat(inPayload(event, "$.reentry_after")).isEqualTo(3);
        assertThat(inPayload(event, "$.miss_count")).isEqualTo(1);

        assertThat(calledToken(agent, session)).as("the counter calls the next ticket at once").isEqualTo(others.get(0));
    }

    @Test
    void aMissPastTheLimitClosesTheTicketAsNoShowInsteadOfReturningIt() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String token = issueAgo(w.a(), 10);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);

        for (int miss = 1; miss <= 2; miss++) {
            calledToken(agent, session);
            assertThat(status(miss(agent, session, null))).isEqualTo(200);
            Map<String, Object> row = ticketRow(token, w.a());
            assertThat(row.get("state")).as("miss " + miss).isEqualTo("waiting");
            assertThat(row.get("miss_count")).isEqualTo(miss);
        }
        assertThat(calledToken(agent, session)).as("the same ticket is called a third time").isEqualTo(token);
        clock.advance(Duration.ofMinutes(2));
        MvcResult third = miss(agent, session, null);

        assertThat(status(third)).as(body(third)).isEqualTo(200);
        assertThat((Object) field(third, "$.ticket")).isNull();
        Map<String, Object> row = ticketRow(token, w.a());
        assertThat(row.get("state")).isEqualTo("no_show");
        assertThat(row.get("miss_count")).isEqualTo(3);
        assertThat(row.get("counter_session_id")).as("the binding is cleared on a terminal state (Invariant 2)").isNull();
        assertThat(row.get("closed_at")).isNotNull();
        assertThat(row.get("wait_seconds")).isNotNull();
        assertThat(row.get("served_at")).isNull();
        List<Map<String, Object>> events = eventsOf(row);
        assertThat(events.stream().map(e -> e.get("event_type")))
                .containsExactly("ticket.issued", "ticket.called", "ticket.missed", "ticket.called", "ticket.missed", "ticket.called", "ticket.no_show");
        assertThat(events.getLast().get("from_state") + ">" + events.getLast().get("to_state")).isEqualTo("called>no_show");
        assertThat(events.stream().filter(e -> "ticket.missed".equals(e.get("event_type"))).map(e -> inPayload(e, "$.score_adjustment_minutes")))
                .as("each return to the queue carries its adjustment").hasSize(2).allMatch(Integer.class::isInstance);
        assertThat(inPayload(events.getLast(), "$.miss_count")).isEqualTo(3);
        MvcResult nothing = next(agent, session);
        assertThat(reason(nothing)).as("a no-show is out of the queue").isEqualTo("no_ticket_waiting");
    }

    @Test
    void aTicketCalledAgainAfterAMissWaitedOnlyWhileItWasWaiting() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String token = issueAgo(w.a(), 5); // queued at BASE - 5 min
        Agent agent = agent(w);
        UUID session = opened(agent, desk);

        calledToken(agent, session); // BASE: 300 s of waiting
        clock.set(BASE.plusSeconds(120));
        miss(agent, session, null); // 120 s being called are not wait
        clock.set(BASE.plusSeconds(420));
        calledToken(agent, session); // 300 s more of waiting
        clock.set(BASE.plusSeconds(500));
        serve(agent, session, null);
        clock.set(BASE.plusSeconds(600));
        complete(agent, session, null, null);

        Map<String, Object> row = ticketRow(token, w.a());
        assertThat(row.get("wait_seconds")).as("Invariant 1: 300 + 300, not queued_at to the last call").isEqualTo(600);
        assertThat(row.get("service_seconds")).isEqualTo(100);
    }

    @Test
    void missingTheTicketOfAClosingSessionResolvesItAndTheSessionCloses() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String token = issueAgo(w.a(), 10);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        calledToken(agent, session);
        assertThat(status(close(agent, session))).isEqualTo(409);

        MvcResult missed = miss(agent, session, null);

        assertThat(status(missed)).isEqualTo(200);
        assertThat((String) field(missed, "$.state")).isEqualTo("closed");
        assertThat(ticketRow(token, w.a()).get("state")).isEqualTo("waiting");
        assertThat(status(current(agent))).isEqualTo(404);
    }

    @Test
    void reannouncingAndMissingNeedACalledTicketInTheCallersOwnSession() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        issueAgo(w.a(), 10);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        assertThat(reason(reannounce(agent, session, null))).as("nothing called yet").isEqualTo("no_ticket_called");
        assertThat(reason(miss(agent, session, null))).isEqualTo("no_ticket_called");

        String token = calledToken(agent, session);
        serve(agent, session, null);
        assertThat(reason(reannounce(agent, session, null))).as("a ticket in service cannot be re-announced").isEqualTo("no_ticket_called");
        assertThat(reason(miss(agent, session, null))).as("a ticket in service cannot be missed").isEqualTo("no_ticket_called");
        assertThat(ticketRow(token, w.a()).get("state")).isEqualTo("serving");
        assertThat(ticketRow(token, w.a()).get("miss_count")).isEqualTo(0);
    }

    @Test
    void reannouncingAndMissingAreRefusedOnAStaleTicketVersionAndLeaveTheTicketAsItWas() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String token = issueAgo(w.a(), 10);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        calledToken(agent, session); // version 1

        assertThat(reason(reannounce(agent, session, 0))).isEqualTo("version_mismatch");
        assertThat(reason(miss(agent, session, 0))).isEqualTo("version_mismatch");

        Map<String, Object> row = ticketRow(token, w.a());
        assertThat(row.get("state")).isEqualTo("called");
        assertThat(row.get("version")).isEqualTo(1);
        assertThat(row.get("announce_count")).isEqualTo(0);
        assertThat(row.get("miss_count")).isEqualTo(0);
    }

    @Test
    void reannouncingAndMissingAreCheckedOnTheServerForPermissionAndOwnership() throws Exception {
        World w = world();
        UUID desk1 = counter(w, "Desk 1", w.a(), 1);
        UUID desk2 = counter(w, "Desk 2", w.a(), 1);
        String token = issueAgo(w.a(), 10);
        Agent owner = agent(w);
        Agent other = agent(w);
        UUID ownersSession = opened(owner, desk1);
        opened(other, desk2);
        calledToken(owner, ownersSession);
        Agent admin = user(Role.ORG_ADMIN, w.site(), w.group());
        Agent reception = user(Role.RECEPTION_OPERATOR, w.site(), w.group());

        for (String action : List.of("reannounce", "miss")) {
            String path = "/api/v1/sessions/" + ownersSession + "/" + action;
            assertThat(status(call(post(path), other.token(), null))).as(action + " on another agent's session").isEqualTo(403);
            assertThat(status(call(post(path), admin.token(), null))).as(action + " as org admin").isEqualTo(403);
            assertThat(status(call(post(path), reception.token(), null))).as(action + " as reception").isEqualTo(403);
            assertThat(status(call(post(path), null, null))).as(action + " unauthenticated").isEqualTo(401);
            assertThat(status(call(post("/api/v1/sessions/" + UUID.randomUUID() + "/" + action), owner.token(), null))).as(action + " unknown session").isEqualTo(404);
        }
        Map<String, Object> row = ticketRow(token, w.a());
        assertThat(row.get("state")).as("nothing changed").isEqualTo("called");
        assertThat(row.get("announce_count")).isEqualTo(0);
        assertThat(row.get("miss_count")).isEqualTo(0);
    }

    // ---- FR-AGT-013, ADR-0008, §19.1: Hold (F8) and resume --------------------------------------------------------

    @Test
    void holdingKeepsTheBindingTakesTheTicketOutOfTheQueueFreesTheCounterAndOnlyTheSameSessionResumesIt() throws Exception {
        World w = world();
        UUID desk1 = counter(w, "Desk 1", w.a(), 1);
        UUID desk2 = counter(w, "Desk 2", w.a(), 1);
        String first = issueAgo(w.a(), 30);
        String second = issueAgo(w.a(), 20);
        String third = issueAgo(w.a(), 10);
        Agent agent = agent(w);
        Agent other = agent(w);
        UUID session = opened(agent, desk1);
        UUID othersSession = opened(other, desk2);
        assertThat(servingToken(agent, session)).isEqualTo(first);
        UUID heldId = (UUID) ticketRow(first, w.a()).get("id");

        MvcResult held = hold(agent, session, null, 2);

        assertThat(status(held)).as(body(held)).isEqualTo(200);
        assertThat((Object) field(held, "$.ticket")).as("the counter is free to call next").isNull();
        assertThat((List<String>) field(held, "$.held[*].token_number")).as("the held-by-me list").containsExactly(first);
        assertThat((String) field(held, "$.held[0].state")).isEqualTo("held");
        assertThat((Integer) field(held, "$.hold_limit")).isEqualTo(3);
        Map<String, Object> row = ticketRow(first, w.a());
        assertThat(row.get("state")).isEqualTo("held");
        assertThat(row.get("counter_session_id")).as("the Session binding is kept (Invariant 2, ADR-0008)").isEqualTo(session);
        assertThat(row.get("version")).isEqualTo(3);
        assertThat(queueOrder(w.a())).as("out of the general queue").containsExactly(second, third);
        assertThat(status(current(agent))).isEqualTo(200);
        assertThat((List<String>) field(current(agent), "$.held[*].token_number")).as("restored after a refresh").containsExactly(first);

        assertThat(reason(hold(other, othersSession, heldId, null))).as("resume only by the same session").isEqualTo("no_ticket_held");
        assertThat(ticketRow(first, w.a()).get("state")).isEqualTo("held");
        assertThat(calledToken(other, othersSession)).as("nobody else can be given a held ticket").isEqualTo(second);

        assertThat(calledToken(agent, session)).as("the freed counter calls the next ticket").isEqualTo(third);
        assertThat(reason(hold(agent, session, heldId, null))).as("one ticket in progress at a time").isEqualTo("ticket_in_progress");
        serve(agent, session, null);
        assertThat(status(complete(agent, session, null, null))).isEqualTo(200);

        assertThat(reason(hold(agent, session, heldId, 1))).as("a stale version").isEqualTo("version_mismatch");
        MvcResult resumed = hold(agent, session, heldId, 3);
        assertThat(status(resumed)).as(body(resumed)).isEqualTo(200);
        assertThat((String) field(resumed, "$.ticket.token_number")).isEqualTo(first);
        assertThat((String) field(resumed, "$.ticket.state")).isEqualTo("serving");
        assertThat((List<Object>) field(resumed, "$.held")).isEmpty();
        assertThat(ticketRow(first, w.a()).get("counter_session_id")).isEqualTo(session);

        assertThat(status(complete(agent, session, null, null))).isEqualTo(200);
        assertThat(ticketRow(first, w.a()).get("state")).isEqualTo("completed");
    }

    @Test
    void holdingAndResumingEachWriteOneEventAndHoldingRecordsHowManyAreHeld() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String token = issueAgo(w.a(), 10);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        servingToken(agent, session);
        UUID ticketId = (UUID) ticketRow(token, w.a()).get("id");
        hold(agent, session, null, null);
        hold(agent, session, ticketId, null);

        List<Map<String, Object>> events = eventsOf(ticketRow(token, w.a()));

        assertThat(events.stream().map(e -> e.get("event_type"))).containsExactly("ticket.issued", "ticket.called", "ticket.serving", "ticket.held", "ticket.serving");
        assertThat(events.stream().map(e -> e.get("seq"))).containsExactly(1, 2, 3, 4, 5);
        assertThat(events.stream().map(e -> e.get("from_state") + ">" + e.get("to_state"))).containsExactly("null>waiting", "waiting>called", "called>serving", "serving>held", "held>serving");
        assertThat(events.get(3).get("counter_id")).isEqualTo(desk);
        assertThat(inPayload(events.get(3), "$.held_count")).isEqualTo(1);
        assertThat(inPayload(events.get(3), "$.session_id")).isEqualTo(session.toString());
    }

    @Test
    void aSessionMayHoldOnlyUpToTheHoldLimit() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        for (int ago : new int[] {50, 40, 30, 20}) issueAgo(w.a(), ago);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        for (int i = 0; i < 3; i++) {
            servingToken(agent, session);
            assertThat(status(hold(agent, session, null, null))).as("hold " + (i + 1)).isEqualTo(200);
        }
        String fourth = servingToken(agent, session);

        MvcResult refused = hold(agent, session, null, null);

        assertThat(status(refused)).isEqualTo(409);
        assertThat(reason(refused)).isEqualTo("hold_limit_reached");
        assertThat(ticketRow(fourth, w.a()).get("state")).as("left as it was").isEqualTo("serving");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE counter_session_id = ? AND state = 'held'", Integer.class, session)).isEqualTo(3);
    }

    @Test
    void theHoldLimitIsPerSessionSoAnotherCounterHoldsItsOwn() throws Exception {
        World w = world();
        UUID desk1 = counter(w, "Desk 1", w.a(), 1);
        UUID desk2 = counter(w, "Desk 2", w.a(), 1);
        for (int ago : new int[] {60, 50, 40, 30, 20}) issueAgo(w.a(), ago);
        Agent first = agent(w);
        Agent second = agent(w);
        UUID firstSession = opened(first, desk1);
        UUID secondSession = opened(second, desk2);
        for (int i = 0; i < 3; i++) {
            servingToken(first, firstSession);
            hold(first, firstSession, null, null);
        }

        servingToken(second, secondSession);

        assertThat(status(hold(second, secondSession, null, null))).isEqualTo(200);
    }

    @Test
    void holdNeedsATicketInServiceAndAnOpenSessionAndTheRightVersion() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        issueAgo(w.a(), 20);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        assertThat(reason(hold(agent, session, null, null))).as("nothing at all").isEqualTo("no_ticket_serving");
        assertThat(reason(hold(agent, session, UUID.randomUUID(), null))).as("resuming a ticket that is not held").isEqualTo("no_ticket_held");
        calledToken(agent, session);
        assertThat(reason(hold(agent, session, null, null))).as("called, not serving").isEqualTo("no_ticket_serving");
        serve(agent, session, null);
        assertThat(reason(hold(agent, session, null, 1))).as("stale version").isEqualTo("version_mismatch");
        assertThat(status(hold(agent, session, null, 2))).isEqualTo(200);

        assertThat(status(close(agent, session))).isEqualTo(409);
        assertThat(reason(hold(agent, session, null, null))).as("a closing session takes no more parking").isEqualTo("session_not_open");
    }

    @Test
    void holdAndResumeAreCheckedOnTheServerForPermissionAndOwnership() throws Exception {
        World w = world();
        UUID desk1 = counter(w, "Desk 1", w.a(), 1);
        UUID desk2 = counter(w, "Desk 2", w.a(), 1);
        String token = issueAgo(w.a(), 10);
        Agent owner = agent(w);
        Agent other = agent(w);
        UUID ownersSession = opened(owner, desk1);
        opened(other, desk2);
        servingToken(owner, ownersSession);
        Agent admin = user(Role.ORG_ADMIN, w.site(), w.group());
        Agent reception = user(Role.RECEPTION_OPERATOR, w.site(), w.group());
        String path = "/api/v1/sessions/" + ownersSession + "/hold";

        assertThat(status(call(post(path), other.token(), null))).as("another agent's session").isEqualTo(403);
        assertThat(status(call(post(path), admin.token(), null))).as("org admin may not serve").isEqualTo(403);
        assertThat(status(call(post(path), reception.token(), null))).isEqualTo(403);
        assertThat(status(call(post(path), null, null))).isEqualTo(401);
        assertThat(status(call(post("/api/v1/sessions/" + UUID.randomUUID() + "/hold"), owner.token(), null))).isEqualTo(404);
        assertThat(ticketRow(token, w.a()).get("state")).as("nothing changed").isEqualTo("serving");
    }

    // ---- FR-AGT-005, FR-AGT-013, §19.3: held tickets must be cleared before the session closes ------------------

    @Test
    void aSessionWithHeldTicketsCannotCloseCleanlyUntilTheyAreResumedAndCompleted() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        issueAgo(w.a(), 20);
        String second = issueAgo(w.a(), 10);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        String held = servingToken(agent, session);
        hold(agent, session, null, null);
        UUID heldId = (UUID) ticketRow(held, w.a()).get("id");

        MvcResult refused = close(agent, session);

        assertThat(status(refused)).isEqualTo(409);
        assertThat(reason(refused)).as("nothing in progress, only the list to clear").isEqualTo("held_tickets_remaining");
        assertThat(jdbc.queryForObject("SELECT state FROM counter_session WHERE id = ?", String.class, session)).isEqualTo("closing");
        assertThat(reason(next(agent, session))).as("closing takes no new calls").isEqualTo("session_not_open");
        assertThat((List<String>) field(current(agent), "$.held[*].token_number")).containsExactly(held);
        assertThat(ticketRow(held, w.a()).get("state")).as("the held ticket was not dropped").isEqualTo("held");
        assertThat(ticketRow(second, w.a()).get("state")).isEqualTo("waiting");

        MvcResult resumed = hold(agent, session, heldId, null);
        assertThat(status(resumed)).as("resuming is how a closing session clears its list").isEqualTo(200);
        assertThat((String) field(resumed, "$.state")).isEqualTo("closing");
        MvcResult done = complete(agent, session, null, null);

        assertThat(status(done)).isEqualTo(200);
        assertThat((String) field(done, "$.state")).as("closing → closed once the last held ticket is resolved").isEqualTo("closed");
        assertThat(status(current(agent))).isEqualTo(404);
    }

    @Test
    void closingWithATicketInProgressAndOneHeldStillNamesTheTicketInProgress() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        issueAgo(w.a(), 20);
        issueAgo(w.a(), 10);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        servingToken(agent, session);
        hold(agent, session, null, null);
        calledToken(agent, session);

        assertThat(reason(close(agent, session))).isEqualTo("ticket_in_progress");
    }

    // ---- FR-AGT-002, FR-AGT-013, §19.3, ADR-0008: force-close --------------------------------------------------

    @Test
    void anAdminForceClosesAStaleSessionAndItsServingAndHeldTicketsReturnToTheFrontWithAnAuditEntry() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String heldA = issueAgo(w.a(), 60);
        String heldB = issueAgo(w.a(), 55);
        String serving = issueAgo(w.a(), 50);
        String waiting1 = issueAgo(w.a(), 20);
        String waiting2 = issueAgo(w.a(), 10);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        for (int i = 0; i < 2; i++) {
            servingToken(agent, session);
            hold(agent, session, null, null);
        }
        assertThat(servingToken(agent, session)).isEqualTo(serving);
        // A waiting ticket someone re-prioritised far ahead: only a Score adjustment can put the returned ones in front of it.
        jdbc.update("UPDATE ticket SET score_adjustment_minutes = 500 WHERE token_number = ? AND service_id = ?", waiting1, w.a());
        Object queuedAt = ticketRow(heldA, w.a()).get("queued_at");
        Agent admin = user(Role.TEAM_ADMIN, w.site(), w.group());
        clock.set(BASE.plusSeconds(90));

        MvcResult result = forceClose(admin, session, "Tablet left in the corridor");

        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat((String) field(result, "$.state")).isEqualTo("force_closed");
        assertThat((Object) field(result, "$.ticket")).isNull();
        assertThat((List<Object>) field(result, "$.held")).isEmpty();
        for (String token : List.of(heldA, heldB, serving)) {
            Map<String, Object> row = ticketRow(token, w.a());
            assertThat(row.get("state")).as(token).isEqualTo("waiting");
            assertThat(row.get("counter_session_id")).as(token + " binding cleared (Invariant 2)").isNull();
            assertThat((Integer) row.get("score_adjustment_minutes")).as(token + " returned by Score adjustment").isGreaterThan(0);
            assertThat(row.get("miss_count")).as("not a Miss").isEqualTo(0);
            assertThat(row.get("closed_at")).isNull();
        }
        assertThat(ticketRow(heldA, w.a()).get("queued_at")).as("queued_at is never rewritten (ADR-0004)").isEqualTo(queuedAt);
        assertThat(queueOrder(w.a())).as("the front of the queue, earliest joined first, ahead of the re-prioritised ticket too")
                .containsExactly(heldA, heldB, serving, waiting1, waiting2);

        Map<String, Object> last = eventsOf(ticketRow(heldA, w.a())).getLast();
        assertThat(last.get("event_type")).isEqualTo("ticket.position_changed");
        assertThat(last.get("from_state") + ">" + last.get("to_state")).isEqualTo("held>waiting");
        assertThat(inPayload(last, "$.reason")).isEqualTo("session_force_closed");
        assertThat(inPayload(last, "$.reentry_position")).isEqualTo("front");
        assertThat(inPayload(last, "$.score_adjustment_minutes")).isEqualTo(ticketRow(heldA, w.a()).get("score_adjustment_minutes"));
        assertThat(eventsOf(ticketRow(serving, w.a())).getLast().get("from_state")).isEqualTo("serving");
        for (String token : List.of(heldA, heldB, serving)) {
            List<Map<String, Object>> events = eventsOf(ticketRow(token, w.a()));
            assertThat(events.stream().map(e -> e.get("seq")).toList()).as("one event per transition, in sequence")
                    .isEqualTo(java.util.stream.IntStream.rangeClosed(1, events.size()).boxed().toList());
        }

        Map<String, Object> audit = jdbc.queryForMap("SELECT actor_id, reason, before::text AS before, after::text AS after FROM audit_log WHERE action = 'session.force_closed' AND entity_id = ?", session);
        assertThat(audit.get("actor_id")).isEqualTo(admin.id());
        assertThat(audit.get("reason")).isEqualTo("Tablet left in the corridor");
        assertThat((List<Object>) JsonPath.read((String) audit.get("after"), "$.returned_tickets")).hasSize(3);
        assertThat(JsonPath.<String>read((String) audit.get("before"), "$.agent_id")).isEqualTo(agent.id().toString());
        assertThat(jdbc.queryForObject("SELECT closed_at FROM counter_session WHERE id = ?", Object.class, session)).isNotNull();

        assertThat(reason(next(agent, session))).as("the agent's stale device gets nothing").isEqualTo("session_not_open");
        assertThat(status(current(agent))).isEqualTo(404);
        assertThat(status(open(agent(w), desk))).as("the counter is free again").isEqualTo(201);
    }

    @Test
    void anOrgAdminMayForceCloseToo() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String token = issueAgo(w.a(), 10);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        calledToken(agent, session);
        Agent admin = user(Role.ORG_ADMIN, w.site(), w.group());

        MvcResult result = forceClose(admin, session, null);

        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat(ticketRow(token, w.a()).get("state")).isEqualTo("waiting");
        assertThat(eventsOf(ticketRow(token, w.a())).getLast().get("from_state")).as("a called ticket is returned too").isEqualTo("called");
        assertThat(jdbc.queryForObject("SELECT reason FROM audit_log WHERE action = 'session.force_closed' AND entity_id = ?", String.class, session)).isNull();
    }

    @Test
    void forceClosingIsForAdminsWithinTheirScopeAndOnlyForALiveSession() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String token = issueAgo(w.a(), 10);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        calledToken(agent, session);
        World elsewhere = world();
        UUID otherGroup = otherGroup(elsewhere);

        assertThat(status(forceClose(agent, session, null))).as("an agent may not force-close, not even their own session").isEqualTo(403);
        assertThat(status(forceClose(user(Role.RECEPTION_OPERATOR, w.site(), w.group()), session, null))).isEqualTo(403);
        assertThat(status(forceClose(user(Role.TEAM_ADMIN, elsewhere.site(), elsewhere.group()), session, null))).as("outside the token's sites").isEqualTo(403);
        assertThat(status(forceClose(user(Role.TEAM_ADMIN, w.site(), otherGroup, new UUID[] {otherGroup}), session, null))).as("outside the token's service groups").isEqualTo(403);
        assertThat(status(call(post("/api/v1/sessions/" + session + "/force-close"), null, null))).isEqualTo(401);
        Agent admin = user(Role.TEAM_ADMIN, w.site(), w.group());
        assertThat(status(forceClose(admin, UUID.randomUUID(), null))).isEqualTo(404);
        assertThat(ticketRow(token, w.a()).get("state")).as("nothing changed").isEqualTo("called");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'session.force_closed' AND entity_id = ?", Integer.class, session)).isZero();

        assertThat(status(forceClose(admin, session, null))).isEqualTo(200);
        MvcResult again = forceClose(admin, session, null);
        assertThat(status(again)).isEqualTo(409);
        assertThat(reason(again)).isEqualTo("session_not_open");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'session.force_closed' AND entity_id = ?", Integer.class, session)).as("audited once").isEqualTo(1);
    }

    @Test
    void forceClosingASessionWithNothingInProgressStillClosesItAndIsAudited() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        Agent admin = user(Role.TEAM_ADMIN, w.site(), w.group());

        MvcResult result = forceClose(admin, session, null);

        assertThat(status(result)).isEqualTo(200);
        assertThat((String) field(result, "$.state")).isEqualTo("force_closed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'session.force_closed' AND entity_id = ?", Integer.class, session)).isEqualTo(1);
        assertThat(status(open(agent, desk))).isEqualTo(201);
    }

    @Test
    void aTicketReturnedByAForceCloseAndCalledAgainWaitedOnlyWhileItWasWaiting() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String token = issueAgo(w.a(), 5); // queued at BASE - 5 min
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        calledToken(agent, session); // BASE: 300 s of waiting
        clock.set(BASE.plusSeconds(120));
        serve(agent, session, null);
        hold(agent, session, null, null); // held time is not wait
        clock.set(BASE.plusSeconds(200));
        forceClose(user(Role.TEAM_ADMIN, w.site(), w.group()), session, null);
        clock.set(BASE.plusSeconds(500));
        Agent next = agent(w);
        UUID again = opened(next, desk);
        calledToken(next, again); // 300 s more of waiting
        serve(next, again, null);
        clock.set(BASE.plusSeconds(560));
        complete(next, again, null, null);

        assertThat(ticketRow(token, w.a()).get("wait_seconds")).as("Invariant 1: 300 + 300, not counting time held").isEqualTo(600);
    }

    // ---- ticket 15: transfer to a Successor ticket (FR-QUE-052, FR-QUE-053, FR-QUE-003, ADR-0006, §19.1, Invariant 4) -----------------

    private MvcResult transfer(Agent by, UUID ticketId, Integer version, String json) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/tickets/" + ticketId + "/transfer");
        if (version != null) request.header("If-Match", "\"" + version + "\"");
        return call(request, by.token(), json);
    }

    private static String transferJson(UUID service, UUID counter, UUID agent, String note) {
        List<String> parts = new ArrayList<>();
        if (service != null) parts.add("\"service_id\":\"" + service + "\"");
        if (counter != null) parts.add("\"counter_id\":\"" + counter + "\"");
        if (agent != null) parts.add("\"agent_id\":\"" + agent + "\"");
        if (note != null) parts.add("\"note\":\"" + note + "\"");
        return "{" + String.join(",", parts) + "}";
    }

    private UUID idOf(String token, UUID service) {
        return (UUID) ticketRow(token, service).get("id");
    }

    private Instant instantOf(UUID ticket, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM ticket WHERE id = ?", java.time.OffsetDateTime.class, ticket).toInstant();
    }

    private Map<String, Object> successorOf(UUID predecessor) {
        return jdbc.queryForMap("SELECT * FROM ticket WHERE predecessor_ticket_id = ?", predecessor);
    }

    private UUID newClass(int headstartMinutes) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO priority_class (id, name_i18n, headstart_minutes, is_default, active, created_at, updated_at) VALUES (?, '{\"en\":\"Senior\"}'::jsonb, ?, false, true, now(), now())",
                id, headstartMinutes);
        return id;
    }

    /** Asserts a refused transfer and that it changed nothing: the ticket is still being served and no successor exists. */
    private void assertNotTransferred(MvcResult result, int status, String reason, String token, UUID service) throws Exception {
        assertThat(status(result)).as(body(result)).isEqualTo(status);
        if (reason != null) assertThat(reason(result)).isEqualTo(reason);
        assertThat(ticketRow(token, service).get("state")).as("still in service").isEqualTo("serving");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE predecessor_ticket_id = ?", Integer.class, idOf(token, service))).as("no successor").isZero();
    }

    @Test
    void aTransferClosesTheServedTicketAndOpensASuccessorInTheTargetQueueWithTheSameTokenVisitAndClassAheadOfLaterArrivals() throws Exception {
        World w = world();
        UUID deskA = counter(w, "Desk 1", w.a(), 1);
        UUID senior = newClass(15);
        clock.set(BASE.minus(Duration.ofMinutes(20)));
        String token = issuance.issue(new IssueCommand(w.a(), Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null, senior)).tokenNumber();
        clock.set(BASE);
        String laterInA = issueAgo(w.a(), 1);
        String b1 = issueAgo(w.b(), 10);
        String b2 = issueAgo(w.b(), 5);
        Agent agent = agent(w);
        UUID session = opened(agent, deskA);
        assertThat(calledToken(agent, session)).as("the class puts it first").isEqualTo(token); // BASE: 1200 s of waiting
        clock.set(BASE.plusSeconds(120));
        assertThat(status(serve(agent, session, null))).isEqualTo(200);
        Map<String, Object> served = ticketRow(token, w.a());
        clock.set(BASE.plusSeconds(300));

        MvcResult result = transfer(agent, (UUID) served.get("id"), (Integer) served.get("version"), transferJson(w.b(), null, null, "Needs a blood test"));

        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat((String) field(result, "$.predecessor.state")).isEqualTo("transferred");
        assertThat((String) field(result, "$.successor.token_number")).as("the visitor keeps the token number").isEqualTo(token);
        assertThat((String) field(result, "$.successor.state")).isEqualTo("waiting");
        assertThat((String) field(result, "$.successor.service.name_i18n.en")).isEqualTo("Laboratory");
        assertThat((Integer) field(result, "$.successor.head_start_minutes")).as("the predecessor's accrued wait, 20 minutes").isEqualTo(20);
        assertThat((Integer) field(result, "$.successor.position")).isEqualTo(1);
        assertThat((Object) field(result, "$.session.ticket")).as("the counter is free").isNull();

        Map<String, Object> closed = ticketRow(token, w.a());
        assertThat(closed.get("state")).as("Invariant 4: terminal").isEqualTo("transferred");
        assertThat(closed.get("counter_session_id")).as("Invariant 2: the binding is cleared").isNull();
        assertThat(closed.get("wait_seconds")).as("its wait stopped when it was called").isEqualTo(1200);
        assertThat(closed.get("service_seconds")).isEqualTo(180);
        assertThat(closed.get("note")).isEqualTo("Needs a blood test");
        assertThat(instantOf(idOf(token, w.a()), "closed_at")).isEqualTo(BASE.plusSeconds(300));

        Map<String, Object> successor = successorOf((UUID) served.get("id"));
        assertThat(successor.get("id").toString()).isEqualTo(field(result, "$.successor.id"));
        assertThat(successor.get("state")).isEqualTo("waiting");
        assertThat(successor.get("service_id")).isEqualTo(w.b());
        assertThat(successor.get("token_number")).isEqualTo(served.get("token_number"));
        assertThat(successor.get("sequence_no")).isEqualTo(served.get("sequence_no"));
        assertThat(successor.get("reset_key")).isEqualTo(served.get("reset_key"));
        assertThat(successor.get("visit_id")).as("the same Visit").isEqualTo(served.get("visit_id"));
        assertThat(successor.get("predecessor_ticket_id")).isEqualTo(served.get("id"));
        assertThat(successor.get("priority_class_id")).as("the class is inherited").isEqualTo(senior);
        assertThat(successor.get("origin_channel")).isEqualTo(served.get("origin_channel"));
        assertThat(successor.get("counter_session_id")).isNull();
        assertThat(successor.get("target_counter_id")).isNull();
        assertThat(successor.get("target_agent_id")).isNull();
        assertThat(successor.get("score_adjustment_minutes")).as("the transfer Head start, 20 minutes of waiting").isEqualTo(20);
        assertThat(instantOf((UUID) successor.get("id"), "queued_at")).as("its wait starts at the transfer").isEqualTo(BASE.plusSeconds(300));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE token_number = ? AND site_id = ? AND predecessor_ticket_id IS NULL", Integer.class, token, w.site()))
                .as("one chain head").isEqualTo(1);

        assertThat(queueOrder(w.b())).as("not sent to the back: 20 + 15 minutes of head start against the 15 and 10 minutes the others waited")
                .containsExactly(token, b1, b2);
        assertThat(queueOrder(w.a())).containsExactly(laterInA);

        List<Map<String, Object>> events = eventsOf(closed);
        assertThat(events.getLast().get("event_type")).isEqualTo("ticket.transferred");
        assertThat(events.getLast().get("from_state") + ">" + events.getLast().get("to_state")).isEqualTo("serving>transferred");
        assertThat(inPayload(events.getLast(), "$.note")).isEqualTo("Needs a blood test");
        assertThat(inPayload(events.getLast(), "$.successor_ticket_id")).isEqualTo(successor.get("id").toString());
        assertThat(events.getLast().get("counter_id")).isEqualTo(deskA);
        List<Map<String, Object>> first = eventsOf(successor);
        assertThat(first).as("Invariant 3: one event for the new ticket").hasSize(1);
        assertThat(first.getFirst().get("seq")).isEqualTo(1);
        assertThat(first.getFirst().get("event_type")).isEqualTo("ticket.issued");
        assertThat(first.getFirst().get("from_state")).isNull();
        assertThat(first.getFirst().get("to_state")).isEqualTo("waiting");
        assertThat(inPayload(first.getFirst(), "$.predecessor_ticket_id")).isEqualTo(served.get("id").toString());
        Map<String, Object> audit = jdbc.queryForMap("SELECT actor_id, reason, after::text AS after FROM audit_log WHERE action = 'ticket.transferred' AND entity_id = ?", served.get("id"));
        assertThat(audit.get("actor_id")).isEqualTo(agent.id());
        assertThat(audit.get("reason")).isEqualTo("Needs a blood test");
        assertThat(JsonPath.<String>read((String) audit.get("after"), "$.successor_ticket_id")).isEqualTo(successor.get("id").toString());

        assertThat(calledToken(agent, session)).as("the counter calls its next ticket").isEqualTo(laterInA);
    }

    @Test
    void aSuccessorIsFirstOnlyAsFarAsItsAccruedWaitCarriesIt() throws Exception {
        World w = world();
        UUID deskA = counter(w, "Desk 1", w.a(), 1);
        String token = issueAgo(w.a(), 5); // waits 300 s: a 5-minute head start
        String longWaiting = issueAgo(w.b(), 30);
        String shortWaiting = issueAgo(w.b(), 2);
        Agent agent = agent(w);
        UUID session = opened(agent, deskA);
        servingToken(agent, session);

        MvcResult result = transfer(agent, idOf(token, w.a()), null, transferJson(w.b(), null, null, "Lab"));

        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat((Integer) field(result, "$.successor.head_start_minutes")).isEqualTo(5);
        assertThat(queueOrder(w.b())).as("ahead of the visitor who arrived after it, behind the one who has waited longer").containsExactly(longWaiting, token, shortWaiting);
    }

    @Test
    void aTransferNeedsANoteAndATarget() throws Exception {
        World w = world();
        UUID deskA = counter(w, "Desk 1", w.a(), 1);
        UUID deskB = counter(w, "Desk 2", w.b(), 1);
        String token = issueAgo(w.a(), 5);
        Agent agent = agent(w);
        Agent colleague = agent(w);
        UUID session = opened(agent, deskA);
        servingToken(agent, session);
        UUID id = idOf(token, w.a());

        assertThat(status(transfer(agent, id, null, null))).as("no body").isEqualTo(400);
        for (String note : new String[] {null, "", "   "}) {
            MvcResult result = transfer(agent, id, null, transferJson(w.b(), null, null, note));
            assertNotTransferred(result, 400, null, token, w.a());
            assertThat((String) field(result, "$.error.code")).isEqualTo("validation_failed");
            assertThat((String) field(result, "$.error.details.fields[0].field")).isEqualTo("note");
        }
        assertNotTransferred(transfer(agent, id, null, transferJson(w.b(), null, null, "x".repeat(1001))), 400, null, token, w.a());
        MvcResult noTarget = transfer(agent, id, null, transferJson(null, null, null, "Lab"));
        assertNotTransferred(noTarget, 400, null, token, w.a());
        assertThat((String) field(noTarget, "$.error.details.fields[0].field")).isEqualTo("service_id");
        assertNotTransferred(transfer(agent, id, null, transferJson(w.a(), null, null, "Same queue")), 400, null, token, w.a());
        assertNotTransferred(transfer(agent, id, null, transferJson(w.b(), deskB, colleague.id(), "Both")), 400, null, token, w.a());
        assertNotTransferred(transfer(agent, id, null, transferJson(UUID.randomUUID(), null, null, "Unknown service")), 400, null, token, w.a());
        assertNotTransferred(transfer(agent, id, null, transferJson(w.b(), UUID.randomUUID(), null, "Unknown counter")), 400, null, token, w.a());
        assertNotTransferred(transfer(agent, id, null, transferJson(w.b(), null, UUID.randomUUID(), "Unknown agent")), 400, null, token, w.a());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ticket.transferred' AND entity_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void aTicketTargetedAtAnAgentWaitsInThatAgentsPersonalQueueAndNoOtherCounterDrawsIt() throws Exception {
        World w = world();
        UUID deskA = counter(w, "Desk 1", w.a(), 1);
        UUID deskB1 = counter(w, "Desk B1", w.b(), 1);
        UUID deskB2 = counter(w, "Desk B2", w.b(), 1);
        String token = issueAgo(w.a(), 20);
        String general = issueAgo(w.b(), 1);
        Agent sender = agent(w);
        Agent other = agent(w);
        Agent target = agent(w);
        UUID senderSession = opened(sender, deskA);
        servingToken(sender, senderSession);

        MvcResult result = transfer(sender, idOf(token, w.a()), null, transferJson(w.b(), null, target.id(), "For Dr. Rahman"));

        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat((String) field(result, "$.successor.agent_id")).isEqualTo(target.id().toString());
        Map<String, Object> successor = successorOf(idOf(token, w.a()));
        assertThat(successor.get("target_agent_id")).isEqualTo(target.id());
        assertThat(successor.get("target_counter_id")).isNull();
        assertThat(queueOrder(w.b())).as("it is first in order, and still in the Service's queue count").containsExactly(token, general);

        UUID otherSession = opened(other, deskB1);
        UUID targetSession = opened(target, deskB2);
        assertThat(calledToken(other, otherSession)).as("the ticket ahead of it in order is not theirs to draw").isEqualTo(general);
        assertThat(status(serve(other, otherSession, null))).isEqualTo(200);
        assertThat(status(complete(other, otherSession, null, null))).isEqualTo(200);
        MvcResult none = next(other, otherSession);
        assertThat(status(none)).isEqualTo(409);
        assertThat(reason(none)).as("only the personal queue is left").isEqualTo("no_ticket_waiting");

        assertThat(calledToken(target, targetSession)).as("its own agent draws it").isEqualTo(token);
        MvcResult missed = miss(target, targetSession, null);
        assertThat(status(missed)).as(body(missed)).isEqualTo(200);
        assertThat(ticketRow(token, w.b()).get("target_agent_id")).as("a Miss keeps it in the personal queue").isEqualTo(target.id());
        assertThat(reason(next(other, otherSession))).isEqualTo("no_ticket_waiting");
        assertThat(calledToken(target, targetSession)).isEqualTo(token);
    }

    @Test
    void aTicketTargetedAtACounterIsDrawnByThatCounterAlone() throws Exception {
        World w = world();
        UUID deskA = counter(w, "Desk 1", w.a(), 1);
        UUID deskB1 = counter(w, "Desk B1", w.b(), 1);
        UUID deskB2 = counter(w, "Desk B2", w.b(), 1);
        String token = issueAgo(w.a(), 10);
        Agent sender = agent(w);
        Agent atB1 = agent(w);
        Agent atB2 = agent(w);
        UUID senderSession = opened(sender, deskA);
        servingToken(sender, senderSession);

        MvcResult result = transfer(sender, idOf(token, w.a()), null, transferJson(w.b(), deskB2, null, "Second desk has the scanner"));

        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat((String) field(result, "$.successor.counter_id")).isEqualTo(deskB2.toString());
        assertThat(ticketRow(token, w.b()).get("zone_id")).as("it waits where that counter is").isEqualTo(w.zone());
        UUID sessionB1 = opened(atB1, deskB1);
        UUID sessionB2 = opened(atB2, deskB2);
        assertThat(reason(next(atB1, sessionB1))).isEqualTo("no_ticket_waiting");
        assertThat(calledToken(atB2, sessionB2)).isEqualTo(token);
    }

    @Test
    void aCounterOrAgentTargetWithoutAServiceMeansTheTicketsOwnService() throws Exception {
        World w = world();
        UUID desk1 = counter(w, "Desk 1", w.a(), 1);
        UUID desk2 = counter(w, "Desk 2", w.a(), 1);
        String token = issueAgo(w.a(), 10);
        Agent sender = agent(w);
        UUID session = opened(sender, desk1);
        servingToken(sender, session);
        UUID id = idOf(token, w.a());

        MvcResult result = transfer(sender, id, null, transferJson(null, desk2, null, "Specialist desk"));

        assertThat(status(result)).as(body(result)).isEqualTo(200);
        Map<String, Object> successor = successorOf(id);
        assertThat(successor.get("service_id")).isEqualTo(w.a());
        assertThat(successor.get("state")).isEqualTo("waiting");
        assertThat(successor.get("target_counter_id")).isEqualTo(desk2);
        assertThat(queues.ordered(w.a(), null).entries().getFirst().target().counterId()).isEqualTo(desk2);
    }

    @Test
    void theTargetMustBeActiveOnTheTicketsOwnSiteAndAbleToServeIt() throws Exception {
        World w = world();
        UUID deskA = counter(w, "Desk 1", w.a(), 1);
        UUID deskB = counter(w, "Desk B", w.b(), 1);
        UUID retired = newService(w.group(), "C", "Retired");
        jdbc.update("UPDATE service SET active = false WHERE id = ?", retired);
        UUID closedDesk = counter(w, "Closed desk", w.b(), 1);
        jdbc.update("UPDATE counter SET active = false WHERE id = ?", closedDesk);
        World elsewhere = world();
        UUID farDesk = counter(elsewhere, "Far desk", elsewhere.a(), 1);
        Agent sender = agent(w);
        Agent disabled = agent(w);
        jdbc.update("UPDATE users SET active = false WHERE id = ?", disabled.id());
        Agent offTeam = user(Role.AGENT, w.site(), null);
        Agent farAgent = agent(elsewhere);
        String token = issueAgo(w.a(), 5);
        UUID session = opened(sender, deskA);
        servingToken(sender, session);
        UUID id = idOf(token, w.a());

        assertNotTransferred(transfer(sender, id, null, transferJson(retired, null, null, "n")), 409, "transfer_target_inactive", token, w.a());
        assertNotTransferred(transfer(sender, id, null, transferJson(elsewhere.a(), null, null, "n")), 409, "transfer_cross_site", token, w.a());
        assertNotTransferred(transfer(sender, id, null, transferJson(w.b(), closedDesk, null, "n")), 409, "transfer_target_inactive", token, w.a());
        assertNotTransferred(transfer(sender, id, null, transferJson(w.b(), farDesk, null, "n")), 409, "transfer_cross_site", token, w.a());
        assertNotTransferred(transfer(sender, id, null, transferJson(w.b(), deskA, null, "n")), 409, "transfer_target_mismatch", token, w.a());
        assertNotTransferred(transfer(sender, id, null, transferJson(w.b(), null, disabled.id(), "n")), 409, "transfer_target_inactive", token, w.a());
        assertNotTransferred(transfer(sender, id, null, transferJson(w.b(), null, offTeam.id(), "n")), 409, "transfer_target_mismatch", token, w.a());
        assertNotTransferred(transfer(sender, id, null, transferJson(w.b(), null, farAgent.id(), "n")), 409, "transfer_target_mismatch", token, w.a());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ticket.transferred' AND entity_id = ?", Integer.class, id)).isZero();
        assertThat(status(transfer(sender, id, null, transferJson(w.b(), deskB, null, "Fine")))).as("a valid target still works").isEqualTo(200);
    }

    @Test
    void onlyTheAgentServingTheTicketOrAnAdminInScopeMayTransferIt() throws Exception {
        World w = world();
        UUID deskA = counter(w, "Desk 1", w.a(), 1);
        String token = issueAgo(w.a(), 5);
        String second = issueAgo(w.a(), 3);
        Agent owner = agent(w);
        UUID session = opened(owner, deskA);
        calledToken(owner, session);
        UUID id = idOf(token, w.a());
        String body = transferJson(w.b(), null, null, "Lab");

        assertThat(reason(transfer(owner, id, null, body))).as("called, not yet in service").isEqualTo("no_ticket_serving");
        assertThat(status(serve(owner, session, null))).isEqualTo(200);
        assertThat(status(transfer(agent(w), id, null, body))).as("another agent's ticket").isEqualTo(403);
        assertThat(status(transfer(user(Role.RECEPTION_OPERATOR, w.site(), w.group()), id, null, body))).as("reception cannot transfer").isEqualTo(403);
        assertThat(status(call(post("/api/v1/tickets/" + id + "/transfer"), null, body))).isEqualTo(401);
        assertThat(status(transfer(owner, UUID.randomUUID(), null, body))).isEqualTo(404);
        World elsewhere = world();
        assertThat(status(transfer(user(Role.TEAM_ADMIN, elsewhere.site(), elsewhere.group()), id, null, body))).as("an admin of another site").isEqualTo(403);
        UUID otherGroup = otherGroup(w);
        assertThat(status(transfer(user(Role.TEAM_ADMIN, w.site(), otherGroup, new UUID[] {otherGroup}), id, null, body))).as("an admin of another Service group").isEqualTo(403);
        assertNotTransferred(transfer(owner, id, 99, body), 409, "version_mismatch", token, w.a());
        assertThat(status(transfer(owner, id, null, "{\"note\":\"x\"}"))).as("no target").isEqualTo(400);

        Agent admin = user(Role.TEAM_ADMIN, w.site(), w.group());
        int version = (Integer) ticketRow(token, w.a()).get("version");
        MvcResult result = transfer(admin, id, version, body);
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE action = 'ticket.transferred' AND entity_id = ?", UUID.class, id)).as("the admin is the actor").isEqualTo(admin.id());
        assertThat(reason(transfer(admin, id, null, body))).as("terminal: it cannot be transferred twice").isEqualTo("no_ticket_serving");
        assertThat(status(transfer(owner, id, null, body))).as("and it is no longer the agent's").isEqualTo(403);
        assertThat(calledToken(owner, session)).as("the agent's desk is free").isEqualTo(second);
    }

    @Test
    void aSessionThatWasClosingBecauseOfTheTicketClosesOnceItIsTransferred() throws Exception {
        World w = world();
        UUID deskA = counter(w, "Desk 1", w.a(), 1);
        String token = issueAgo(w.a(), 5);
        Agent agent = agent(w);
        UUID session = opened(agent, deskA);
        servingToken(agent, session);
        assertThat(reason(close(agent, session))).isEqualTo("ticket_in_progress");

        MvcResult result = transfer(agent, idOf(token, w.a()), null, transferJson(w.b(), null, null, "Lab"));

        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat((String) field(result, "$.session.state")).isEqualTo("closed");
        assertThat(status(current(agent))).isEqualTo(404);
        assertThat(status(open(agent, deskA))).as("the counter is free").isEqualTo(201);
    }

    @Test
    void aSuccessorCanBeTransferredInTurnAndTheVisitKeepsOneTokenAcrossTheChain() throws Exception {
        World w = world();
        UUID deskA = counter(w, "Desk 1", w.a(), 1);
        UUID deskB = counter(w, "Desk B", w.b(), 1);
        String token = issueAgo(w.a(), 10);
        Agent one = agent(w);
        Agent two = agent(w);
        UUID sessionOne = opened(one, deskA);
        UUID sessionTwo = opened(two, deskB);
        servingToken(one, sessionOne);
        UUID first = idOf(token, w.a());
        clock.set(BASE.plusSeconds(300));
        assertThat(status(transfer(one, first, null, transferJson(w.b(), null, null, "To the lab")))).isEqualTo(200);
        clock.set(BASE.plusSeconds(600));
        assertThat(servingToken(two, sessionTwo)).as("it waited 300 s in the lab queue").isEqualTo(token);
        UUID second = (UUID) successorOf(first).get("id");
        clock.set(BASE.plusSeconds(700));

        MvcResult back = transfer(two, second, null, transferJson(w.a(), null, null, "Back to the doctor"));

        assertThat(status(back)).as(body(back)).isEqualTo(200);
        assertThat((Integer) field(back, "$.successor.head_start_minutes")).as("the second ticket's own accrued wait, 5 minutes").isEqualTo(5);
        Map<String, Object> third = successorOf(second);
        assertThat(third.get("predecessor_ticket_id")).isEqualTo(second);
        assertThat(third.get("visit_id")).isEqualTo(jdbc.queryForObject("SELECT visit_id FROM ticket WHERE id = ?", UUID.class, first));
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT visit_id) FROM ticket WHERE token_number = ? AND site_id = ?", Integer.class, token, w.site())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE token_number = ? AND site_id = ?", Integer.class, token, w.site())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT wait_seconds FROM ticket WHERE id = ?", Integer.class, first)).as("each wait belongs to its own ticket").isEqualTo(600);
        assertThat(jdbc.queryForObject("SELECT wait_seconds FROM ticket WHERE id = ?", Integer.class, second)).isEqualTo(300);
    }

    @Test
    void theTransferTargetsListTheActiveServicesCountersAndAgentsOfTheSessionsSiteOnly() throws Exception {
        World w = world();
        UUID deskA = counter(w, "Desk 1", w.a(), 1);
        UUID deskB = counter(w, "Desk B", w.b(), 1);
        UUID retired = newService(w.group(), "C", "Retired");
        jdbc.update("UPDATE service SET active = false WHERE id = ?", retired);
        World elsewhere = world();
        counter(elsewhere, "Far desk", elsewhere.a(), 1);
        Agent me = agent(w);
        Agent colleague = agent(w);
        Agent disabled = agent(w);
        jdbc.update("UPDATE users SET active = false WHERE id = ?", disabled.id());
        agent(elsewhere);
        UUID session = opened(me, deskA);

        MvcResult result = call(get("/api/v1/sessions/" + session + "/transfer-targets"), me.token(), null);

        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat((List<String>) field(result, "$.services[*].id")).containsExactlyInAnyOrder(w.a().toString(), w.b().toString());
        assertThat((List<String>) field(result, "$.services[*].group_name_i18n.en")).as("each Service names its group").containsOnly("Outpatient");
        assertThat((List<String>) field(result, "$.counters[*].id")).as("not its own counter, nor another site's").containsExactly(deskB.toString());
        assertThat((List<String>) field(result, "$.counters[0].service_ids")).containsExactly(w.b().toString());
        assertThat((List<String>) field(result, "$.agents[*].id")).as("active colleagues of the site, not the agent themselves").containsExactly(colleague.id().toString());
        assertThat((List<String>) field(result, "$.agents[0].service_ids")).containsExactlyInAnyOrder(w.a().toString(), w.b().toString());
        assertThat(status(call(get("/api/v1/sessions/" + session + "/transfer-targets"), colleague.token(), null))).as("another agent's session").isEqualTo(403);
        assertThat(status(call(get("/api/v1/sessions/" + UUID.randomUUID() + "/transfer-targets"), me.token(), null))).isEqualTo(404);
        assertThat(status(call(get("/api/v1/sessions/" + session + "/transfer-targets"), user(Role.RECEPTION_OPERATOR, w.site(), w.group()).token(), null))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/sessions/" + session + "/transfer-targets"), user(Role.TEAM_ADMIN, w.site(), w.group()).token(), null))).isEqualTo(200);
    }

    // ---- ticket 16: breaks and agent availability (FR-AGT-020..022, FR-AGT-024, §19.3) ------------------------------

    private UUID newBreakType(String name, Integer maxMinutes) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO break_type (id, name_i18n, max_minutes, active, created_at, updated_at) VALUES (?, ?::jsonb, ?, true, now(), now())",
                id, "{\"en\":\"" + name + "\"}", maxMinutes);
        return id;
    }

    /** F9: starts the break of {@code type}, or ends the current one when {@code type} is null. */
    private MvcResult takeBreak(Agent agent, UUID session, UUID type) throws Exception {
        return call(post("/api/v1/sessions/" + session + "/break"), agent.token(), type == null ? null : "{\"break_type_id\":\"" + type + "\"}");
    }

    private MvcResult setAvailability(Agent admin, UUID agentId, String status, UUID type, String reason) throws Exception {
        StringBuilder json = new StringBuilder("{\"status\":\"" + status + "\"");
        if (type != null) json.append(",\"break_type_id\":\"").append(type).append("\"");
        if (reason != null) json.append(",\"reason\":\"").append(reason).append("\"");
        return call(put("/api/v1/agents/" + agentId + "/availability"), admin.token(), json.append("}").toString());
    }

    private List<String> auditActions(UUID session) {
        return jdbc.queryForList("SELECT action FROM audit_log WHERE entity = 'counter_session' AND entity_id = ? ORDER BY occurred_at, id", String.class, session);
    }

    @Test
    void anAgentStartsATypedBreakAndStopsReceivingTicketsAtOnceAndEndingItPutsThemBackInService() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String waiting = issueAgo(w.a(), 10);
        UUID lunch = newBreakType("Lunch", 30);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);

        MvcResult started = takeBreak(agent, session, lunch);

        assertThat(status(started)).as(body(started)).isEqualTo(200);
        assertThat((String) field(started, "$.state")).isEqualTo("on_break");
        assertThat((String) field(started, "$.break.type.name_i18n.en")).isEqualTo("Lunch");
        assertThat((Integer) field(started, "$.break.type.max_minutes")).isEqualTo(30);
        assertThat((String) field(started, "$.break.started_at")).isEqualTo(BASE.toString());
        assertThat(jdbc.queryForObject("SELECT state FROM counter_session WHERE id = ?", String.class, session)).isEqualTo("on_break");
        MvcResult refused = next(agent, session);
        assertThat(status(refused)).as("no new assignment while on a break").isEqualTo(409);
        assertThat(reason(refused)).isEqualTo("session_not_open");
        assertThat(ticketRow(waiting, w.a()).get("state")).as("the ticket stays in the queue").isEqualTo("waiting");
        assertThat((String) field(current(agent), "$.break.type.name_i18n.en")).as("a refresh restores the break").isEqualTo("Lunch");
        assertThat(status(open(agent(w), desk))).as("the counter is still occupied on a break").isEqualTo(409);

        clock.advance(Duration.ofMinutes(12));
        MvcResult ended = takeBreak(agent, session, null);

        assertThat(status(ended)).as(body(ended)).isEqualTo(200);
        assertThat((String) field(ended, "$.state")).isEqualTo("open");
        assertThat((Object) field(ended, "$.break")).isNull();
        assertThat(calledToken(agent, session)).as("back in service").isEqualTo(waiting);
    }

    @Test
    void aBreakIsRecordedWithItsStartEndAndTypeAndIsAuditedBothWays() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        UUID prayer = newBreakType("Prayer", 20);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        clock.advance(Duration.ofSeconds(1));

        takeBreak(agent, session, prayer);
        clock.advance(Duration.ofMinutes(25));
        takeBreak(agent, session, null);

        Map<String, Object> record = jdbc.queryForMap("SELECT * FROM break_record WHERE counter_session_id = ?", session);
        assertThat(record.get("break_type_id")).isEqualTo(prayer);
        assertThat(((java.sql.Timestamp) record.get("started_at")).toInstant()).isEqualTo(BASE.plusSeconds(1));
        assertThat(((java.sql.Timestamp) record.get("ended_at")).toInstant()).isEqualTo(BASE.plusSeconds(1).plus(Duration.ofMinutes(25)));
        assertThat(record.get("started_by")).isEqualTo(agent.id());
        assertThat(record.get("ended_by")).isEqualTo(agent.id());
        assertThat(auditActions(session)).containsExactly("session.opened", "session.break_started", "session.break_ended");
        Map<String, Object> ended = jdbc.queryForMap("SELECT actor_id, after::text AS after FROM audit_log WHERE action = 'session.break_ended' AND entity_id = ?", session);
        assertThat(ended.get("actor_id")).isEqualTo(agent.id());
        assertThat((Integer) JsonPath.read((String) ended.get("after"), "$.duration_seconds")).isEqualTo(25 * 60);
        assertThat((Boolean) JsonPath.read((String) ended.get("after"), "$.overran")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM break_record WHERE counter_session_id = ? AND ended_at IS NULL", Integer.class, session)).isZero();
    }

    @Test
    void aBreakNeedsTheTicketInProgressResolvedFirstAndAHeldTicketDoesNotBlockIt() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        issueAgo(w.a(), 30);
        issueAgo(w.a(), 20);
        UUID meeting = newBreakType("Meeting", null);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        calledToken(agent, session);

        MvcResult whileCalled = takeBreak(agent, session, meeting);
        assertThat(status(whileCalled)).isEqualTo(409);
        assertThat(reason(whileCalled)).as("a called ticket").isEqualTo("ticket_in_progress");
        serve(agent, session, null);
        assertThat(reason(takeBreak(agent, session, meeting))).as("a ticket in service").isEqualTo("ticket_in_progress");
        assertThat(jdbc.queryForObject("SELECT state FROM counter_session WHERE id = ?", String.class, session)).as("nothing changed").isEqualTo("open");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM break_record WHERE counter_session_id = ?", Integer.class, session)).isZero();

        assertThat(status(hold(agent, session, null, null))).isEqualTo(200);
        MvcResult whileHeld = takeBreak(agent, session, meeting);
        assertThat(status(whileHeld)).as(body(whileHeld)).isEqualTo(200);
        assertThat((List<Object>) field(whileHeld, "$.held")).as("the held ticket stays held").hasSize(1);
        assertThat((Object) field(whileHeld, "$.break.type.max_minutes")).as("no maximum").isNull();
    }

    @Test
    void aBreakIsRefusedForAnUnknownOrSwitchedOffTypeOrOneAlreadyRunningOrAnOpenSessionThatIsNotOnOne() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        UUID lunch = newBreakType("Lunch", 30);
        UUID retired = newBreakType("Retired", 30);
        jdbc.update("UPDATE break_type SET active = false WHERE id = ?", retired);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);

        MvcResult unknown = takeBreak(agent, session, UUID.randomUUID());
        assertThat(status(unknown)).isEqualTo(400);
        assertThat((String) field(unknown, "$.error.details.fields[0].field")).isEqualTo("break_type_id");
        assertThat(status(takeBreak(agent, session, retired))).as("a switched-off type").isEqualTo(400);
        MvcResult notOnBreak = takeBreak(agent, session, null);
        assertThat(status(notOnBreak)).isEqualTo(409);
        assertThat(reason(notOnBreak)).isEqualTo("not_on_break");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM break_record WHERE counter_session_id = ?", Integer.class, session)).isZero();

        assertThat(status(takeBreak(agent, session, lunch))).isEqualTo(200);
        MvcResult twice = takeBreak(agent, session, lunch);
        assertThat(status(twice)).isEqualTo(409);
        assertThat(reason(twice)).isEqualTo("already_on_break");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM break_record WHERE counter_session_id = ?", Integer.class, session)).as("one break at a time").isEqualTo(1);
        assertThat(status(call(post("/api/v1/sessions/" + UUID.randomUUID() + "/break"), agent.token(), null))).isEqualTo(404);
    }

    @Test
    void aSessionThatIsClosingTakesNoBreak() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        issueAgo(w.a(), 10);
        UUID lunch = newBreakType("Lunch", 30);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        calledToken(agent, session);
        assertThat(status(close(agent, session))).isEqualTo(409);

        MvcResult refused = takeBreak(agent, session, lunch);

        assertThat(status(refused)).isEqualTo(409);
        assertThat(reason(refused)).isEqualTo("session_not_open");
    }

    @Test
    void closingOrForceClosingASessionOnABreakEndsTheBreakAndKeepsItsTime() throws Exception {
        World w = world();
        UUID desk1 = counter(w, "Desk 1", w.a(), 1);
        UUID desk2 = counter(w, "Desk 2", w.a(), 1);
        UUID lunch = newBreakType("Lunch", 30);
        Agent closer = agent(w);
        Agent stale = agent(w);
        UUID closing = opened(closer, desk1);
        UUID forced = opened(stale, desk2);
        takeBreak(closer, closing, lunch);
        takeBreak(stale, forced, lunch);
        clock.advance(Duration.ofMinutes(5));

        MvcResult closed = close(closer, closing);
        MvcResult forceClosed = forceClose(user(Role.TEAM_ADMIN, w.site(), w.group()), forced, "walked away");

        assertThat((String) field(closed, "$.state")).isEqualTo("closed");
        assertThat((String) field(forceClosed, "$.state")).isEqualTo("force_closed");
        for (UUID session : List.of(closing, forced)) {
            assertThat(jdbc.queryForObject("SELECT ended_at IS NOT NULL FROM break_record WHERE counter_session_id = ?", Boolean.class, session)).isTrue();
            assertThat(auditActions(session)).contains("session.break_ended");
        }
        assertThat(status(open(agent(w), desk1))).as("the counter is free").isEqualTo(201);
    }

    @Test
    void breakTypesAreConfiguredWithNamesInEveryLanguageAndAnOptionalMaximumAndAgentsMayReadThem() throws Exception {
        World w = world();
        Agent orgAdmin = user(Role.ORG_ADMIN, w.site(), null);
        Agent agent = agent(w);

        MvcResult created = call(post("/api/v1/break-types"), orgAdmin.token(), "{\"name_i18n\":{\"en\":\"Lunch\",\"bn\":\"দুপুরের খাবার\"},\"max_minutes\":45}");
        assertThat(status(created)).as(body(created)).isEqualTo(201);
        String id = field(created, "$.id");
        assertThat((String) field(created, "$.name_i18n.bn")).isEqualTo("দুপুরের খাবার");
        assertThat((Integer) field(created, "$.max_minutes")).isEqualTo(45);
        assertThat((Boolean) field(created, "$.active")).isTrue();

        MvcResult noMax = call(post("/api/v1/break-types"), orgAdmin.token(), "{\"name_i18n\":{\"en\":\"System issue\"}}");
        assertThat(status(noMax)).as(body(noMax)).isEqualTo(201);
        assertThat((Object) field(noMax, "$.max_minutes")).isNull();

        clock.advance(Duration.ofSeconds(1));
        MvcResult replaced = call(put("/api/v1/break-types/" + id), orgAdmin.token(), "{\"name_i18n\":{\"en\":\"Lunch break\",\"bn\":\"দুপুরের বিরতি\"},\"max_minutes\":60}");
        assertThat(status(replaced)).as(body(replaced)).isEqualTo(200);
        assertThat((Integer) field(replaced, "$.max_minutes")).isEqualTo(60);

        assertThat((List<String>) field(call(get("/api/v1/break-types"), agent.token(), null), "$.items[*].id")).as("an agent reads them to choose one").contains(id);
        clock.advance(Duration.ofSeconds(1));
        assertThat(status(call(post("/api/v1/break-types/" + id + "/deactivate"), orgAdmin.token(), "{\"reason\":\"no longer offered\"}"))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT active FROM break_type WHERE id = ?::uuid", Boolean.class, id)).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(status(call(post("/api/v1/break-types/" + id + "/activate"), orgAdmin.token(), null))).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE entity = 'break_type' AND entity_id = ?::uuid ORDER BY occurred_at, id", String.class, id))
                .containsExactly("break_type.created", "break_type.updated", "break_type.deactivated", "break_type.activated");
    }

    @Test
    void aBreakTypeNeedsANameInTheDefaultLanguageAndAMaximumInRangeAndOnlyOrgAdminsChangeThem() throws Exception {
        World w = world();
        Agent orgAdmin = user(Role.ORG_ADMIN, w.site(), null);
        Agent agent = agent(w);
        Agent teamAdmin = user(Role.TEAM_ADMIN, w.site(), w.group());
        Agent reception = user(Role.RECEPTION_OPERATOR, w.site(), w.group());
        String valid = "{\"name_i18n\":{\"en\":\"Lunch\"},\"max_minutes\":30}";

        assertThat(status(call(post("/api/v1/break-types"), orgAdmin.token(), "{\"name_i18n\":{\"bn\":\"দুপুর\"}}"))).as("no default-language name").isEqualTo(400);
        assertThat(status(call(post("/api/v1/break-types"), orgAdmin.token(), "{\"name_i18n\":{\"en\":\"Lunch\"},\"max_minutes\":0}"))).isEqualTo(400);
        assertThat(status(call(post("/api/v1/break-types"), orgAdmin.token(), "{\"name_i18n\":{\"en\":\"Lunch\"},\"max_minutes\":5000}"))).isEqualTo(400);
        assertThat(status(call(post("/api/v1/break-types"), orgAdmin.token(), "{\"name_i18n\":{\"xx\":\"Lunch\",\"en\":\"Lunch\"}}"))).as("a language that is not installed").isEqualTo(400);
        for (Agent denied : List.of(agent, teamAdmin, reception)) {
            assertThat(status(call(post("/api/v1/break-types"), denied.token(), valid))).as("create").isEqualTo(403);
        }
        assertThat(status(call(get("/api/v1/break-types"), reception.token(), null))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/break-types"), null, valid))).isEqualTo(401);
        assertThat(status(call(put("/api/v1/break-types/" + UUID.randomUUID()), orgAdmin.token(), valid))).isEqualTo(404);
    }

    @Test
    void theBreakReportCountsTimeAndOverrunsPerAgentAndBreakTypeForTheAdminsScope() throws Exception {
        World w = world();
        UUID desk1 = counter(w, "Desk 1", w.a(), 1);
        UUID desk2 = counter(w, "Desk 2", w.a(), 1);
        UUID lunch = newBreakType("Lunch", 30);
        UUID prayer = newBreakType("Prayer", null);
        Agent first = agent(w);
        Agent second = agent(w);
        UUID s1 = opened(first, desk1);
        UUID s2 = opened(second, desk2);
        for (int minutes : new int[] {20, 40}) {
            takeBreak(first, s1, lunch);
            clock.advance(Duration.ofMinutes(minutes));
            takeBreak(first, s1, null);
        }
        takeBreak(first, s1, prayer);
        clock.advance(Duration.ofMinutes(10));
        takeBreak(first, s1, null);
        takeBreak(second, s2, lunch);
        clock.advance(Duration.ofMinutes(35));
        takeBreak(second, s2, null);
        takeBreak(second, s2, prayer); // still running: no duration yet, so not in the report
        Agent admin = user(Role.TEAM_ADMIN, w.site(), w.group());

        MvcResult report = call(get("/api/v1/break-report?agent_id=" + first.id() + "&from=" + BASE.minusSeconds(60)), admin.token(), null);

        assertThat(status(report)).as(body(report)).isEqualTo(200);
        assertThat((List<Integer>) field(report, "$.rows[*].count")).containsExactlyInAnyOrder(2, 1);
        Map<String, Object> lunchRow = ((List<Map<String, Object>>) field(report, "$.rows[?(@.break_type.id == '" + lunch + "')]")).getFirst();
        assertThat(lunchRow).containsEntry("count", 2).containsEntry("total_seconds", 3600).containsEntry("average_seconds", 1800).containsEntry("overruns", 1);
        assertThat(lunchRow).containsEntry("agent_id", first.id().toString());
        Map<String, Object> prayerRow = ((List<Map<String, Object>>) field(report, "$.rows[?(@.break_type.id == '" + prayer + "')]")).getFirst();
        assertThat(prayerRow).containsEntry("count", 1).containsEntry("total_seconds", 600).containsEntry("overruns", 0);

        MvcResult everyone = call(get("/api/v1/break-report?break_type_id=" + lunch), admin.token(), null);
        assertThat((List<String>) field(everyone, "$.rows[*].agent_id")).contains(first.id().toString(), second.id().toString());
        assertThat((List<Integer>) field(call(get("/api/v1/break-report?break_type_id=" + lunch + "&agent_id=" + second.id()), admin.token(), null), "$.rows[*].overruns")).containsExactly(1);
        assertThat((List<Object>) field(call(get("/api/v1/break-report?agent_id=" + first.id() + "&to=" + BASE.minusSeconds(60)), admin.token(), null), "$.rows")).as("outside the range").isEmpty();

        World elsewhere = world();
        Agent outside = user(Role.TEAM_ADMIN, elsewhere.site(), elsewhere.group(), new UUID[] {elsewhere.group()});
        assertThat((List<Object>) field(call(get("/api/v1/break-report?agent_id=" + first.id()), outside.token(), null), "$.rows")).as("another site's breaks are not theirs to see").isEmpty();
        assertThat(status(call(get("/api/v1/break-report"), first.token(), null))).as("an agent runs no reports").isEqualTo(403);
        assertThat(status(call(get("/api/v1/break-report"), null, null))).isEqualTo(401);
        assertThat(status(call(get("/api/v1/break-report?from=" + BASE + "&to=" + BASE.minusSeconds(1)), admin.token(), null))).isEqualTo(400);
    }

    @Test
    void anAdminSetsAnAgentsAvailabilityDirectlyAndItStopsAndRestoresTheirAssignments() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String waiting = issueAgo(w.a(), 10);
        UUID system = newBreakType("System issue", 15);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        Agent admin = user(Role.TEAM_ADMIN, w.site(), w.group());
        clock.advance(Duration.ofSeconds(1));

        MvcResult listed = call(get("/api/v1/agents/availability"), admin.token(), null);
        assertThat(status(listed)).as(body(listed)).isEqualTo(200);
        assertThat((List<String>) field(listed, "$.items[?(@.agent_id == '" + agent.id() + "')].status")).containsExactly("available");

        MvcResult forced = setAvailability(admin, agent.id(), "on_break", system, "Network outage at desk");

        assertThat(status(forced)).as(body(forced)).isEqualTo(200);
        assertThat((String) field(forced, "$.status")).isEqualTo("on_break");
        assertThat((String) field(forced, "$.session_id")).isEqualTo(session.toString());
        assertThat((String) field(forced, "$.break.type.name_i18n.en")).isEqualTo("System issue");
        assertThat(reason(next(agent, session))).as("the agent receives nothing").isEqualTo("session_not_open");
        assertThat((String) field(current(agent), "$.state")).as("their console learns of it").isEqualTo("on_break");
        assertThat(jdbc.queryForObject("SELECT started_by FROM break_record WHERE counter_session_id = ?", UUID.class, session)).as("the admin started it").isEqualTo(admin.id());

        clock.advance(Duration.ofMinutes(3));
        MvcResult restored = setAvailability(admin, agent.id(), "available", null, null);

        assertThat((String) field(restored, "$.status")).isEqualTo("available");
        assertThat(calledToken(agent, session)).isEqualTo(waiting);
        assertThat(jdbc.queryForObject("SELECT ended_by FROM break_record WHERE counter_session_id = ?", UUID.class, session)).isEqualTo(admin.id());
        List<Map<String, Object>> audits = jdbc.queryForList("SELECT actor_id, reason, before::text AS before, after::text AS after FROM audit_log WHERE action = 'agent.availability_changed' AND entity_id = ? ORDER BY occurred_at, id", session);
        assertThat(audits).hasSize(2);
        assertThat(audits.get(0).get("actor_id")).isEqualTo(admin.id());
        assertThat(audits.get(0).get("reason")).isEqualTo("Network outage at desk");
        assertThat((String) JsonPath.read((String) audits.get(0).get("before"), "$.status")).isEqualTo("available");
        assertThat((String) JsonPath.read((String) audits.get(0).get("after"), "$.status")).isEqualTo("on_break");
        assertThat((String) JsonPath.read((String) audits.get(1).get("after"), "$.status")).isEqualTo("available");
        assertThat(auditActions(session)).as("one audit entry per forced change, not two").containsExactly("session.opened", "agent.availability_changed", "agent.availability_changed");
    }

    @Test
    void anAdminMayNotForceABreakOnAgentsWithATicketInProgressNorOnOneWithNoSessionAndInvalidRequestsAreRefused() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        issueAgo(w.a(), 10);
        UUID lunch = newBreakType("Lunch", 30);
        Agent agent = agent(w);
        Agent idle = agent(w);
        UUID session = opened(agent, desk);
        calledToken(agent, session);
        Agent admin = user(Role.ORG_ADMIN, w.site(), w.group());

        assertThat(reason(setAvailability(admin, agent.id(), "on_break", lunch, null))).isEqualTo("ticket_in_progress");
        MvcResult none = setAvailability(admin, idle.id(), "on_break", lunch, null);
        assertThat(status(none)).isEqualTo(409);
        assertThat(reason(none)).isEqualTo("no_live_session");
        assertThat(status(setAvailability(admin, UUID.randomUUID(), "on_break", lunch, null))).isEqualTo(404);
        assertThat(status(setAvailability(admin, agent.id(), "asleep", null, null))).isEqualTo(400);
        assertThat(status(call(put("/api/v1/agents/" + agent.id() + "/availability"), admin.token(), "{}"))).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT state FROM counter_session WHERE id = ?", String.class, session)).isEqualTo("open");

        serve(agent, session, null);
        complete(agent, session, null, null);
        assertThat(status(setAvailability(admin, agent.id(), "on_break", null, null))).as("no type").isEqualTo(400);
        assertThat(status(setAvailability(admin, agent.id(), "on_break", lunch, null))).isEqualTo(200);
        MvcResult again = setAvailability(admin, agent.id(), "on_break", lunch, null);
        assertThat(reason(again)).isEqualTo("already_on_break");
        assertThat(status(setAvailability(admin, agent.id(), "available", null, null))).isEqualTo(200);
        assertThat(status(setAvailability(admin, agent.id(), "available", null, null))).as("already available: nothing to change").isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'agent.availability_changed' AND entity_id = ?", Integer.class, session)).as("audited when it changes").isEqualTo(2);
    }

    @Test
    void onlyAdminsWithinTheirScopeSetAvailabilityAndEveryBreakActionIsCheckedOnTheServer() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        UUID lunch = newBreakType("Lunch", 30);
        Agent agent = agent(w);
        Agent other = agent(w);
        UUID session = opened(agent, desk);
        World elsewhere = world();
        UUID otherGroup = otherGroup(w);

        for (Agent denied : List.of(agent, other, user(Role.RECEPTION_OPERATOR, w.site(), w.group()))) {
            assertThat(status(setAvailability(denied, agent.id(), "on_break", lunch, null))).as("force-set by " + denied.id()).isEqualTo(403);
            assertThat(status(call(get("/api/v1/agents/availability"), denied.token(), null))).isEqualTo(403);
        }
        assertThat(status(setAvailability(user(Role.TEAM_ADMIN, elsewhere.site(), elsewhere.group()), agent.id(), "on_break", lunch, null))).as("another site").isEqualTo(403);
        assertThat(status(setAvailability(user(Role.TEAM_ADMIN, w.site(), otherGroup, new UUID[] {otherGroup}), agent.id(), "on_break", lunch, null))).as("another service group").isEqualTo(403);
        assertThat((List<Object>) field(call(get("/api/v1/agents/availability"), user(Role.TEAM_ADMIN, elsewhere.site(), elsewhere.group()).token(), null), "$.items[?(@.agent_id == '" + agent.id() + "')]"))
                .as("the list holds only agents in scope").isEmpty();
        assertThat(status(call(put("/api/v1/agents/" + agent.id() + "/availability"), null, "{\"status\":\"available\"}"))).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT state FROM counter_session WHERE id = ?", String.class, session)).as("nothing changed").isEqualTo("open");

        // F9 is the agent's own: nobody else's session, and not reception.
        assertThat(status(takeBreak(other, session, lunch))).as("another agent's session").isEqualTo(403);
        assertThat(status(takeBreak(user(Role.RECEPTION_OPERATOR, w.site(), w.group()), session, lunch))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/sessions/" + session + "/break"), null, null))).isEqualTo(401);
        assertThat(status(takeBreak(agent, session, lunch))).isEqualTo(200);
    }

    // ---- FR-QUE-032, ADR-0004: the call timeout ----------------------------------------------------------------

    /** A service that lets a counter have up to {@code limit} of its tickets in progress (FR-AGT-010, FR-AGT-011). */
    private void makeParallel(UUID service, int limit) {
        jdbc.update("UPDATE service SET parallel_serving = true, parallel_limit = ? WHERE id = ?", limit, service);
    }

    private MvcResult returnCalled(Agent agent, UUID session, UUID ticket, Integer version) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/sessions/" + session + "/return" + (ticket == null ? "" : "?ticket_id=" + ticket));
        if (version != null) request.header("If-Match", "\"" + version + "\"");
        return call(request, agent.token(), null);
    }

    private MvcResult callSpecific(Agent agent, UUID session, Object ticket, String reason) throws Exception {
        String reasonJson = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return call(post("/api/v1/sessions/" + session + "/call"), agent.token(), "{" + (ticket == null ? "" : "\"ticket_id\":\"" + ticket + "\"") + (ticket == null ? reasonJson.replaceFirst(",", "") : reasonJson) + "}");
    }

    /** Calls next and returns the token number of the ticket that call added to those in progress (the last one). */
    private String lastCalledToken(Agent agent, UUID session) throws Exception {
        MvcResult called = next(agent, session);
        assertThat(status(called)).as(body(called)).isEqualTo(200);
        List<String> tokens = field(called, "$.tickets[*].token_number");
        return tokens.getLast();
    }

    private UUID ticketId(String token, UUID service) {
        return (UUID) ticketRow(token, service).get("id");
    }

    @Test
    void aCalledTicketNobodyActsOnPromptsTheAgentOnceAfterTheTimeoutAndTheAgentMayReturnItWithItsOriginalWait() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String first = issueAgo(w.a(), 40);
        String second = issueAgo(w.a(), 20);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        MvcResult called = next(agent, session);
        assertThat((String) field(called, "$.ticket.token_number")).isEqualTo(first);
        assertThat((Integer) field(called, "$.call_timeout_seconds")).as("the default is 90 seconds").isEqualTo(90);
        assertThat((Boolean) field(called, "$.ticket.call_timed_out")).isFalse();
        UUID ticket = ticketId(first, w.a());
        Map<String, Object> before = ticketRow(first, w.a());

        clock.advance(Duration.ofSeconds(89));
        sessionService.promptTimedOutCalls();
        assertThat(ticketRow(first, w.a()).get("call_timeout_notified_at")).as("not yet").isNull();
        assertThat(status(returnCalled(agent, session, null, null))).as("nor may it be returned yet").isEqualTo(409);

        clock.advance(Duration.ofSeconds(1));
        assertThat(sessionService.promptTimedOutCalls()).as("the Agent is prompted at the timeout").isPositive();
        Map<String, Object> prompted = ticketRow(first, w.a());
        Object promptedAt = prompted.get("call_timeout_notified_at");
        assertThat(promptedAt).isNotNull();
        assertThat(sessionService.promptTimedOutCalls()).as("once for this call, however often the check runs").isZero();
        assertThat(ticketRow(first, w.a()).get("call_timeout_notified_at")).isEqualTo(promptedAt);
        assertThat(prompted.get("state")).as("a prompt is not a transition").isEqualTo("called");
        assertThat(prompted.get("version")).as("the Agent's screen keeps its version").isEqualTo(before.get("version"));
        assertThat(eventsOf(prompted)).hasSameSizeAs(eventsOf(before));
        MvcResult restored = current(agent);
        assertThat((Boolean) field(restored, "$.ticket.call_timed_out")).as("a refreshed console still shows the prompt").isTrue();

        int version = field(restored, "$.ticket.version");
        MvcResult returned = returnCalled(agent, session, ticket, version);
        assertThat(status(returned)).as(body(returned)).isEqualTo(200);
        assertThat((Object) field(returned, "$.ticket")).as("the counter is free").isNull();
        assertThat((Boolean) field(returned, "$.can_call")).isTrue();
        Map<String, Object> row = ticketRow(first, w.a());
        assertThat(row.get("state")).isEqualTo("waiting");
        assertThat(row.get("counter_session_id")).as("the binding is cleared (Invariant 2)").isNull();
        assertThat(row.get("queued_at")).as("queued_at is never rewritten (ADR-0004)").isEqualTo(before.get("queued_at"));
        assertThat(row.get("miss_count")).as("a timeout is not a Miss").isEqualTo(0);
        assertThat(row.get("wait_seconds")).isNull();
        assertThat(row.get("score_adjustment_minutes")).isEqualTo(0);
        assertThat(queueOrder(w.a())).as("it lands where it stood").containsExactly(first, second);

        Map<String, Object> event = eventsOf(row).getLast();
        assertThat(event.get("event_type")).isEqualTo("ticket.position_changed");
        assertThat(event.get("from_state") + ">" + event.get("to_state")).isEqualTo("called>waiting");
        assertThat(inPayload(event, "$.reason")).isEqualTo("call_timeout");
        assertThat(inPayload(event, "$.score_adjustment_minutes")).as("the adjustment applied is on the event (ADR-0004)").isEqualTo(0);

        assertThat(calledToken(agent, session)).as("called again, it is the same visitor first in line").isEqualTo(first);
        sessionService.promptTimedOutCalls();
        Object afterFirstCall = ticketRow(first, w.a()).get("call_timeout_notified_at");
        assertThat(afterFirstCall).as("the new call has not timed out, so the old prompt stands").isEqualTo(promptedAt);
        clock.advance(Duration.ofSeconds(90));
        sessionService.promptTimedOutCalls();
        assertThat(ticketRow(first, w.a()).get("call_timeout_notified_at")).as("and it is prompted again on its own timeout").isNotEqualTo(promptedAt);
    }

    @Test
    void aTicketReturnedAfterATimeoutKeepsItsScoreAdjustmentSoItsPlaceIsRestoredNotMoved() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String first = issueAgo(w.a(), 60);
        issueAgo(w.a(), 50);
        issueAgo(w.a(), 40);
        jdbc.update("UPDATE ticket SET score_adjustment_minutes = 7 WHERE token_number = ? AND service_id = ?", first, w.a());
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        assertThat(calledToken(agent, session)).isEqualTo(first);
        clock.advance(Duration.ofSeconds(120));

        MvcResult returned = returnCalled(agent, session, null, null);

        assertThat(status(returned)).as(body(returned)).isEqualTo(200);
        Map<String, Object> row = ticketRow(first, w.a());
        assertThat(row.get("score_adjustment_minutes")).isEqualTo(7);
        assertThat(inPayload(eventsOf(row).getLast(), "$.score_adjustment_minutes")).isEqualTo(7);
        assertThat(queueOrder(w.a()).getFirst()).isEqualTo(first);
    }

    @Test
    void aCalledTicketIsReturnedOnlyByItsOwnAgentOnceItHasTimedOutAndOnlyWhileItIsCalled() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        UUID desk2 = counter(w, "Desk 2", w.a(), 1);
        String token = issueAgo(w.a(), 10);
        Agent agent = agent(w);
        Agent other = agent(w);
        UUID session = opened(agent, desk);
        UUID otherSession = opened(other, desk2);
        assertThat(reason(returnCalled(agent, session, null, null))).as("nothing called").isEqualTo("no_ticket_called");
        calledToken(agent, session);
        clock.advance(Duration.ofSeconds(200));

        assertThat(status(returnCalled(other, session, null, null))).as("another agent's session").isEqualTo(403);
        assertThat(status(returnCalled(other, otherSession, null, null))).as("nothing called on their own").isEqualTo(409);
        assertThat(status(call(post("/api/v1/sessions/" + session + "/return"), null, null))).isEqualTo(401);
        assertThat(status(call(post("/api/v1/sessions/" + session + "/return"), user(Role.ORG_ADMIN, w.site(), w.group()).token(), null))).as("org admin").isEqualTo(403);
        assertThat(reason(returnCalled(agent, session, null, 99))).as("a stale version").isEqualTo("version_mismatch");
        assertThat(ticketRow(token, w.a()).get("state")).as("nothing changed").isEqualTo("called");

        serve(agent, session, null);
        assertThat(reason(returnCalled(agent, session, null, null))).as("a ticket in service is not returned").isEqualTo("no_ticket_called");
    }

    @Test
    void aReturnResolvesAClosingSessionAndTheTimeoutCanBeSwitchedOffByConfiguration() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        issueAgo(w.a(), 10);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        calledToken(agent, session);
        assertThat(status(close(agent, session))).as("closing while a ticket is called").isEqualTo(409);
        clock.advance(Duration.ofSeconds(91));

        MvcResult returned = returnCalled(agent, session, null, null);

        assertThat(status(returned)).as(body(returned)).isEqualTo(200);
        assertThat((String) field(returned, "$.state")).as("returning the last ticket of a closing session closes it (§19.3)").isEqualTo("closed");
    }

    // ---- FR-AGT-012, FR-SEC-040: a specific ticket, called out of order -----------------------------------------

    @Test
    void anAgentCallsASpecificWaitingTicketOutOfOrderWithAReasonAndItIsAuditedAndWrittenToTheTicketsEvents() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1, w.b(), 2);
        String oldest = issueAgo(w.a(), 50);
        String middle = issueAgo(w.a(), 30);
        String chosen = issueAgo(w.b(), 5);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        UUID chosenId = ticketId(chosen, w.b());

        MvcResult result = callSpecific(agent, session, chosenId, "Visitor is frail and asked to be seen");

        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat((String) field(result, "$.ticket.token_number")).isEqualTo(chosen);
        assertThat((String) field(result, "$.ticket.state")).isEqualTo("called");
        assertThat((Boolean) field(result, "$.can_call")).isFalse();
        Map<String, Object> row = ticketRow(chosen, w.b());
        assertThat(row.get("state")).isEqualTo("called");
        assertThat(row.get("counter_session_id")).isEqualTo(session);
        assertThat(queueOrder(w.a())).as("everyone else keeps their place").containsExactly(oldest, middle);

        Map<String, Object> event = eventsOf(row).getLast();
        assertThat(event.get("event_type")).isEqualTo("ticket.called");
        assertThat(event.get("from_state") + ">" + event.get("to_state")).isEqualTo("waiting>called");
        assertThat(event.get("counter_id")).isEqualTo(desk);
        assertThat(inPayload(event, "$.out_of_order")).isEqualTo(true);
        assertThat(inPayload(event, "$.reason")).isEqualTo("Visitor is frail and asked to be seen");
        assertThat(inPayload(event, "$.announce")).as("the display announces it like any call").isEqualTo(true);

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_id, reason, before::text AS before, after::text AS after FROM audit_log WHERE action = 'ticket.called_out_of_order' AND entity_id = ?", chosenId);
        assertThat(audit.get("actor_id")).isEqualTo(agent.id());
        assertThat(audit.get("reason")).isEqualTo("Visitor is frail and asked to be seen");
        assertThat(JsonPath.<Integer>read((String) audit.get("before"), "$.position")).as("where it stood in its queue").isEqualTo(1);
        assertThat(JsonPath.<String>read((String) audit.get("after"), "$.counter_id")).isEqualTo(desk.toString());

        assertThat(status(serve(agent, session, null))).isEqualTo(200);
        assertThat(status(complete(agent, session, null, null))).isEqualTo(200);
        assertThat(calledToken(agent, session)).as("the queue carries on in order").isEqualTo(oldest);
    }

    @Test
    void anOutOfOrderCallNeedsATicketAndAReasonAndAnythingTheCounterCannotCallIsRefused() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        String waiting = issueAgo(w.a(), 20);
        String elsewhere = issueAgo(w.b(), 20);
        String taken = issueAgo(w.a(), 15);
        String targeted = issueAgo(w.a(), 10);
        Agent agent = agent(w);
        Agent colleague = agent(w);
        jdbc.update("UPDATE ticket SET target_agent_id = ? WHERE token_number = ? AND service_id = ?", colleague.id(), targeted, w.a());
        UUID session = opened(agent, desk);
        UUID waitingId = ticketId(waiting, w.a());
        jdbc.update("UPDATE ticket SET state = 'paused' WHERE id = ?", ticketId(taken, w.a()));

        MvcResult noReason = callSpecific(agent, session, waitingId, null);
        assertThat(status(noReason)).isEqualTo(400);
        assertThat((String) field(noReason, "$.error.details.fields[0].field")).isEqualTo("reason");
        assertThat(status(callSpecific(agent, session, waitingId, "   "))).as("a blank reason").isEqualTo(400);
        assertThat(status(callSpecific(agent, session, waitingId, "x".repeat(1001)))).as("too long").isEqualTo(400);
        MvcResult noTicket = callSpecific(agent, session, null, "Because");
        assertThat(status(noTicket)).isEqualTo(400);
        assertThat((String) field(noTicket, "$.error.details.fields[0].field")).isEqualTo("ticket_id");
        assertThat(status(callSpecific(agent, session, UUID.randomUUID(), "Because"))).as("unknown ticket").isEqualTo(404);

        assertThat(reason(callSpecific(agent, session, ticketId(elsewhere, w.b()), "Because"))).as("a Service this counter does not serve").isEqualTo("ticket_not_callable");
        assertThat(reason(callSpecific(agent, session, ticketId(taken, w.a()), "Because"))).as("a paused ticket").isEqualTo("ticket_not_callable");
        assertThat(reason(callSpecific(agent, session, ticketId(targeted, w.a()), "Because"))).as("waits in another agent's personal queue").isEqualTo("ticket_not_callable");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ticket.called_out_of_order' AND entity_id IN (?, ?, ?, ?)", Integer.class,
                        waitingId, ticketId(elsewhere, w.b()), ticketId(taken, w.a()), ticketId(targeted, w.a())))
                .as("no refused call is audited").isZero();

        assertThat(status(callSpecific(agent, session, waitingId, "Because"))).isEqualTo(200);
        assertThat(reason(callSpecific(agent, session, waitingId, "Again"))).as("a ticket already called").isEqualTo("ticket_not_callable");
        String more = issueAgo(w.a(), 5);
        assertThat(reason(callSpecific(agent, session, ticketId(more, w.a()), "Because"))).as("the desk is busy").isEqualTo("ticket_in_progress");
    }

    @Test
    void anOutOfOrderCallIsCheckedOnTheServerForPermissionOwnershipSiteAndTheSessionsState() throws Exception {
        World w = world();
        World elsewhere = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        UUID desk2 = counter(w, "Desk 2", w.a(), 1);
        String token = issueAgo(w.a(), 10);
        String foreign = issueAgo(elsewhere.a(), 10);
        Agent owner = agent(w);
        Agent other = agent(w);
        UUID session = opened(owner, desk);
        opened(other, desk2);
        UUID ticket = ticketId(token, w.a());
        Agent admin = user(Role.ORG_ADMIN, w.site(), w.group());
        Agent reception = user(Role.RECEPTION_OPERATOR, w.site(), w.group());

        assertThat(status(callSpecific(other, session, ticket, "Because"))).as("another agent's session").isEqualTo(403);
        assertThat(status(callSpecific(admin, session, ticket, "Because"))).as("org admin").isEqualTo(403);
        assertThat(status(callSpecific(reception, session, ticket, "Because"))).as("reception").isEqualTo(403);
        assertThat(status(call(post("/api/v1/sessions/" + session + "/call"), null, "{}"))).as("unauthenticated").isEqualTo(401);
        assertThat(status(callSpecific(owner, UUID.randomUUID(), ticket, "Because"))).as("unknown session").isEqualTo(404);
        assertThat(status(callSpecific(owner, session, ticketId(foreign, elsewhere.a()), "Because"))).as("a ticket of another site").isEqualTo(403);
        assertThat(ticketRow(token, w.a()).get("state")).as("nothing changed").isEqualTo("waiting");

        UUID lunch = UUID.randomUUID();
        jdbc.update("INSERT INTO break_type (id, name_i18n, created_at, updated_at) VALUES (?, '{\"en\":\"Lunch\"}'::jsonb, now(), now())", lunch);
        assertThat(status(call(post("/api/v1/sessions/" + session + "/break"), owner.token(), "{\"break_type_id\":\"" + lunch + "\"}"))).isEqualTo(200);
        assertThat(reason(callSpecific(owner, session, ticket, "Because"))).as("on a break").isEqualTo("session_not_open");
    }

    // ---- FR-AGT-010, FR-AGT-011: parallel serving ----------------------------------------------------------------

    @Test
    void aParallelServiceLetsACounterHaveUpToItsMaximumInProgressAndEachIsServedAndCompletedByName() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        makeParallel(w.a(), 2);
        String t1 = issueAgo(w.a(), 30);
        String t2 = issueAgo(w.a(), 20);
        String t3 = issueAgo(w.a(), 10);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);

        MvcResult one = next(agent, session);
        assertThat((Boolean) field(one, "$.can_call")).as("room for a second").isTrue();
        MvcResult two = next(agent, session);
        assertThat(status(two)).as(body(two)).isEqualTo(200);
        assertThat((List<String>) field(two, "$.tickets[*].token_number")).containsExactly(t1, t2);
        assertThat((String) field(two, "$.ticket.token_number")).as("ticket is the one called first").isEqualTo(t1);
        assertThat((Boolean) field(two, "$.can_call")).as("the maximum is reached").isFalse();
        MvcResult third = next(agent, session);
        assertThat(status(third)).isEqualTo(409);
        assertThat(reason(third)).isEqualTo("ticket_in_progress");

        UUID second = ticketId(t2, w.a());
        MvcResult served = call(post("/api/v1/sessions/" + session + "/serve?ticket_id=" + second), agent.token(), null);
        assertThat(status(served)).as(body(served)).isEqualTo(200);
        assertThat(ticketRow(t2, w.a()).get("state")).isEqualTo("serving");
        assertThat(ticketRow(t1, w.a()).get("state")).as("the other is still only called").isEqualTo("called");
        assertThat(status(complete(agent, session, null, null))).as("without a name, the ticket in service is the only one that can complete").isEqualTo(200);
        assertThat(ticketRow(t2, w.a()).get("state")).isEqualTo("completed");
        assertThat(ticketRow(t1, w.a()).get("state")).isEqualTo("called");

        MvcResult again = next(agent, session);
        assertThat((List<String>) field(again, "$.tickets[*].token_number")).as("room again").containsExactly(t1, t3);
        assertThat(reason(next(agent, session))).isEqualTo("ticket_in_progress");
    }

    @Test
    void aDeskWithAServiceThatIsNotParallelStaysOneAtATimeAndSkipsTheHeadsItCannotTakeYet() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1, w.b(), 1);
        makeParallel(w.a(), 2);
        String a1 = issueAgo(w.a(), 60);
        String b1 = issueAgo(w.b(), 30);
        String a2 = issueAgo(w.a(), 5);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);

        assertThat(calledToken(agent, session)).isEqualTo(a1);
        assertThat(lastCalledToken(agent, session)).as("B's ticket scores higher but B is not parallel, so it waits its turn").isEqualTo(a2);
        assertThat(reason(next(agent, session))).as("full for A, and B cannot join").isEqualTo("ticket_in_progress");
        assertThat(ticketRow(b1, w.b()).get("state")).isEqualTo("waiting");
        for (int i = 0; i < 2; i++) {
            assertThat(status(serve(agent, session, null))).isEqualTo(200);
            assertThat(status(complete(agent, session, null, null))).isEqualTo(200);
        }
        assertThat(calledToken(agent, session)).isEqualTo(b1);
        assertThat(reason(next(agent, session))).as("B is not parallel: one at a time").isEqualTo("ticket_in_progress");
    }

    @Test
    void anOutOfOrderCallAndAResumeRespectTheSameConcurrencyLimit() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        makeParallel(w.a(), 2);
        String t1 = issueAgo(w.a(), 30);
        issueAgo(w.a(), 20);
        String t3 = issueAgo(w.a(), 10);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        assertThat(calledToken(agent, session)).isEqualTo(t1);
        serve(agent, session, null);
        MvcResult held = hold(agent, session, null, null);
        assertThat(status(held)).as(body(held)).isEqualTo(200);
        UUID heldId = ticketId(t1, w.a());

        assertThat(status(callSpecific(agent, session, ticketId(t3, w.a()), "Because"))).as("one in progress, room for another").isEqualTo(200);
        assertThat(status(next(agent, session))).as("two in progress: the maximum").isEqualTo(200);
        assertThat(reason(hold(agent, session, heldId, null))).as("the desk is full, so a held ticket waits").isEqualTo("ticket_in_progress");
    }

    @Test
    void aServiceThatIsNotParallelKeepsTheOneAtATimeDeskAndTheSessionReportsIt() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1);
        issueAgo(w.a(), 20);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        assertThat((Boolean) field(current(agent), "$.can_call")).isTrue();
        MvcResult called = next(agent, session);
        assertThat((List<Object>) field(called, "$.tickets")).hasSize(1);
        assertThat((Boolean) field(called, "$.can_call")).isFalse();
    }

    // ---- NFR-PERF-003: console actions acknowledge within 500 ms at P95 ----------------------------------------

    @Test
    void consoleActionsAcknowledgeWithinHalfASecondAtP95AgainstALoadedQueue() throws Exception {
        World w = world();
        UUID desk = counter(w, "Desk 1", w.a(), 1, w.b(), 2);
        newOutcome(w.a(), "resolved");
        for (int i = 0; i < 150; i++) issueAgo(i % 3 == 0 ? w.b() : w.a(), 200 - i);
        Agent agent = agent(w);
        UUID session = opened(agent, desk);
        UUID outcome = jdbc.queryForObject("SELECT id FROM outcome_code WHERE service_id = ?", UUID.class, w.a());
        List<Long> millis = new ArrayList<>();

        for (int i = 0; i < 40; i++) {
            long t0 = System.nanoTime();
            MvcResult called = next(agent, session);
            millis.add((System.nanoTime() - t0) / 1_000_000);
            assertThat(status(called)).as(body(called)).isEqualTo(200);
            boolean hasOutcome = "Consultation".equals(field(called, "$.ticket.service.name_i18n.en"));
            t0 = System.nanoTime();
            MvcResult served = serve(agent, session, null);
            millis.add((System.nanoTime() - t0) / 1_000_000);
            assertThat(status(served)).isEqualTo(200);
            t0 = System.nanoTime();
            MvcResult done = complete(agent, session, hasOutcome ? outcome : null, null);
            millis.add((System.nanoTime() - t0) / 1_000_000);
            assertThat(status(done)).as(body(done)).isEqualTo(200);
        }

        Collections.sort(millis);
        long p95 = millis.get((int) Math.ceil(millis.size() * 0.95) - 1);
        assertThat(p95).as("P95 of %s actions, in ms: %s", millis.size(), millis).isLessThan(500);
    }

    // ---- helpers for the concurrent tests ----------------------------------------------------------------------

    private static <T> List<T> inParallel(List<? extends Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(120, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
