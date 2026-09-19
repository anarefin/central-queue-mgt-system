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
