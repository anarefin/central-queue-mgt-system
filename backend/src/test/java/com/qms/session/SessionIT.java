package com.qms.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * from ticket 12, Re-announce and Miss: FR-DSP-028, FR-QUE-050, -051, ADR-0004 and ADR-0005.
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
        assertThat((List<String>) field(result, "$.counters[*].id")).as("not its own counter, nor another site's").containsExactly(deskB.toString());
        assertThat((List<String>) field(result, "$.counters[0].service_ids")).containsExactly(w.b().toString());
        assertThat((List<String>) field(result, "$.agents[*].id")).as("active colleagues of the site, not the agent themselves").containsExactly(colleague.id().toString());
        assertThat((List<String>) field(result, "$.agents[0].service_ids")).containsExactlyInAnyOrder(w.a().toString(), w.b().toString());
        assertThat(status(call(get("/api/v1/sessions/" + session + "/transfer-targets"), colleague.token(), null))).as("another agent's session").isEqualTo(403);
        assertThat(status(call(get("/api/v1/sessions/" + UUID.randomUUID() + "/transfer-targets"), me.token(), null))).isEqualTo(404);
        assertThat(status(call(get("/api/v1/sessions/" + session + "/transfer-targets"), user(Role.RECEPTION_OPERATOR, w.site(), w.group()).token(), null))).isEqualTo(403);
        assertThat(status(call(get("/api/v1/sessions/" + session + "/transfer-targets"), user(Role.TEAM_ADMIN, w.site(), w.group()).token(), null))).isEqualTo(200);
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
