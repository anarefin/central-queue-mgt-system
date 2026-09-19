package com.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.issuance.ActorType;
import com.qms.issuance.Channels;
import com.qms.issuance.IssuanceService;
import com.qms.issuance.IssueCommand;
import com.qms.issuance.TicketResponse;
import com.qms.platform.realtime.RealtimePublisher;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
 * Ticket 19 against real PostgreSQL: every issued ticket and queue view carries an honest wait estimate as a rounded range
 * (FR-QUE-040, FR-QUE-041, FR-QUE-042, FR-ISS-005), and it is recomputed and published as the queue moves
 * ({@code queue.estimate_changed}, {@code ticket.position_changed}, SRS §21.4). The service expects 10 minutes per ticket.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, WaitEstimationIT.Recording.class})
class WaitEstimationIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();

    /** Stands in for the realtime hub and remembers what was published, in order. */
    static class Recorder implements RealtimePublisher {
        record Published(String topic, String type, Map<String, Object> data) {}

        final List<Published> published = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(String topic, String type, Instant occurredAt, Map<String, Object> data) {
            published.add(new Published(topic, type, data));
        }

        @Override
        public void principalChanged(String subject) {}
    }

    @TestConfiguration
    static class Recording {
        @Bean
        @Primary
        Recorder recorder() {
            return new Recorder();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-estimation");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssuanceService issuance;
    @Autowired Recorder recorder;

    @BeforeEach
    void forget() {
        recorder.published.clear();
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private record World(UUID site, UUID zone, UUID group, UUID service) {}

    private record Person(UUID id, String token) {}

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
        return new World(site, zone, group, service(group, "A"));
    }

    private UUID service(UUID group, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, ?, 10, 30, '[\"reception\",\"kiosk\"]'::jsonb, 'both')",
                id, group, prefix);
        return id;
    }

    private UUID counter(World w, String label, UUID... services) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, ?)", id, w.zone(), label);
        for (UUID service : services) jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", id, service);
        return id;
    }

    private Person user(Role role, World w, boolean onTeam) throws Exception {
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
        if (onTeam) jdbc.update("INSERT INTO team_member (team_id, user_id) SELECT id, ? FROM team WHERE service_group_id = ?", user, w.group());
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        return new Person(user, JsonPath.read(body(result), "$.access_token"));
    }

    private TicketResponse issue(World w) {
        return issuance.issue(new IssueCommand(w.service(), Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null));
    }

    /** Issues {@code count} tickets and closes them as completed with the given service time, one minute apart from {@code start}. */
    private void completed(World w, int count, int serviceSeconds, Instant start) {
        for (int i = 0; i < count; i++) {
            UUID id = issue(w).id();
            Timestamp closed = Timestamp.from(start.plusSeconds(60L * i));
            jdbc.update("UPDATE ticket SET state = 'completed', closed_at = ?, served_at = ?, service_seconds = ?, wait_seconds = 0 WHERE id = ?", closed, closed, serviceSeconds, id);
        }
        recorder.published.clear();
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andReturn();
    }

    private UUID openSession(Person agent, UUID counter) throws Exception {
        MvcResult result = call(post("/api/v1/sessions"), agent.token(), "{\"counter_id\":\"" + counter + "\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(201);
        return UUID.fromString(JsonPath.read(body(result), "$.id"));
    }

    private void ok(MvcResult result) throws Exception {
        assertThat(status(result)).as(body(result)).isEqualTo(200);
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** {@code [low, high]} at {@code path} of a response. */
    private static List<Integer> range(MvcResult result, String path) throws Exception {
        Map<String, Integer> range = JsonPath.read(body(result), path);
        return List.of(range.get("low"), range.get("high"));
    }

    private List<Recorder.Published> events(UUID service, String type) {
        synchronized (recorder.published) {
            return recorder.published.stream().filter(p -> p.topic().equals("queue:" + service) && p.type().equals(type)).toList();
        }
    }

    private static List<Integer> estimateOf(Recorder.Published event) {
        WaitEstimate estimate = (WaitEstimate) event.data().get("estimated_wait_minutes");
        return List.of(estimate.low(), estimate.high());
    }

    // ---- FR-QUE-040: tickets ahead / max(open counters, 1) x average handling time -----------------------------

    @Test
    void theIssuanceResponseCarriesTheEstimateOfTheTicketsAheadAtTheExpectedHandlingTime() {
        World w = world();
        // No counter is open, so the divisor is 1, and with no completed ticket yet the service's expected 10 minutes applies.
        TicketResponse first = issue(w);
        TicketResponse second = issue(w);
        TicketResponse third = issue(w);

        assertThat(first.position()).isEqualTo(1);
        assertThat(first.estimatedWait()).isEqualTo(new WaitEstimate(0, 5));
        assertThat(second.estimatedWait()).isEqualTo(new WaitEstimate(10, 15));
        assertThat(third.estimatedWait()).isEqualTo(new WaitEstimate(20, 25));
    }

    @Test
    void theEstimateIsDividedByTheCountersOpenForTheService() throws Exception {
        World w = world();
        openSession(user(Role.AGENT, w, true), counter(w, "Desk 1", w.service()));
        openSession(user(Role.AGENT, w, true), counter(w, "Desk 2", w.service()));

        for (int i = 0; i < 4; i++) issue(w);
        TicketResponse fifth = issue(w);

        // Four ahead, two counters open, 10 minutes each: 20 minutes.
        assertThat(fifth.position()).isEqualTo(5);
        assertThat(fifth.estimatedWait()).isEqualTo(new WaitEstimate(20, 25));
    }

    @Test
    void aCounterOnABreakOrChosenForAnotherServiceDoesNotCountAsOpen() throws Exception {
        World w = world();
        UUID other = service(w.group(), "B");
        openSession(user(Role.AGENT, w, true), counter(w, "Desk 1", w.service()));
        openSession(user(Role.AGENT, w, true), counter(w, "Desk 2", w.service()));
        UUID onBreak = openSession(user(Role.AGENT, w, true), counter(w, "Desk 3", w.service()));
        jdbc.update("UPDATE counter_session SET state = 'on_break' WHERE id = ?", onBreak);
        openSession(user(Role.AGENT, w, true), counter(w, "Desk 4", other));

        for (int i = 0; i < 4; i++) issue(w);
        TicketResponse fifth = issue(w);

        // Two of the four sessions take tickets of this service: 4 ahead / 2 x 10 = 20, not 4 / 3 or 4 / 4 x 10.
        assertThat(fifth.estimatedWait()).isEqualTo(new WaitEstimate(20, 25));
    }

    // ---- FR-QUE-041: trailing 20 completed tickets, expected time below 5 samples -------------------------------

    @Test
    void fewerThanFiveCompletedTicketsLeaveTheExpectedHandlingTimeInForce() {
        World w = world();
        completed(w, 4, 1800, Instant.parse("2026-09-19T08:00:00Z"));

        issue(w);
        assertThat(issue(w).estimatedWait()).isEqualTo(new WaitEstimate(10, 15));
    }

    @Test
    void fromFiveCompletedTicketsTheirAverageReplacesTheExpectedTime() {
        World w = world();
        completed(w, 5, 1200, Instant.parse("2026-09-19T08:00:00Z"));

        issue(w);
        assertThat(issue(w).estimatedWait()).isEqualTo(new WaitEstimate(20, 25));
    }

    @Test
    void onlyTheLatestTwentyCompletedTicketsOfTheServiceAreAveraged() {
        World w = world();
        completed(w, 10, 6000, Instant.parse("2026-09-19T07:00:00Z"));
        completed(w, 20, 600, Instant.parse("2026-09-19T08:00:00Z"));
        completed(world(), 20, 3000, Instant.parse("2026-09-19T09:00:00Z"));

        issue(w);
        // The ten long ones are older than the latest twenty, and another service's tickets are not this service's.
        assertThat(issue(w).estimatedWait()).isEqualTo(new WaitEstimate(10, 15));
    }

    // ---- FR-QUE-042 / FR-ISS-005: every read shows a range, and it moves with the queue --------------------------

    @Test
    void theQueueSnapshotTheTicketReadAndTheSiteServicesShowTheEstimateAsARange() throws Exception {
        World w = world();
        Person staff = user(Role.RECEPTION_OPERATOR, w, false);
        TicketResponse first = issue(w);
        issue(w);
        issue(w);

        MvcResult queue = call(get("/api/v1/queues/" + w.service()), staff.token(), null);
        ok(queue);
        // A visitor joining now would have all three in front.
        assertThat(range(queue, "$.estimated_wait_minutes")).isEqualTo(List.of(30, 35));

        MvcResult services = call(get("/api/v1/sites/" + w.site() + "/services"), staff.token(), null);
        ok(services);
        assertThat(range(services, "$.items[0].estimated_wait_minutes")).isEqualTo(List.of(30, 35));

        MvcResult ticket = call(get("/api/v1/tickets/" + first.id()), staff.token(), null);
        ok(ticket);
        assertThat(range(ticket, "$.estimated_wait_minutes")).isEqualTo(List.of(0, 5));
    }

    @Test
    void theEstimateFollowsTheQueueAsTicketsAreCalled() throws Exception {
        World w = world();
        Person agent = user(Role.AGENT, w, true);
        UUID session = openSession(agent, counter(w, "Desk 1", w.service()));
        TicketResponse first = issue(w);
        issue(w);
        TicketResponse third = issue(w);
        MvcResult before = call(get("/api/v1/tickets/" + third.id()), agent.token(), null);
        assertThat(range(before, "$.estimated_wait_minutes")).isEqualTo(List.of(20, 25));

        ok(call(post("/api/v1/sessions/" + session + "/next"), agent.token(), null));

        MvcResult after = call(get("/api/v1/tickets/" + third.id()), agent.token(), null);
        assertThat((Integer) JsonPath.read(body(after), "$.position")).isEqualTo(2);
        assertThat(range(after, "$.estimated_wait_minutes")).isEqualTo(List.of(10, 15));
        // A ticket that has left the queue has no place in it and so no estimate.
        MvcResult called = call(get("/api/v1/tickets/" + first.id()), agent.token(), null);
        assertThat(JsonPath.<Object>read(body(called), "$.estimated_wait_minutes")).isNull();
    }

    // ---- events ---------------------------------------------------------------------------------------------------

    @Test
    void issuingPublishesTheRecomputedEstimateAndTheNewTicketsPlace() {
        World w = world();
        issue(w);
        issue(w);
        recorder.published.clear();

        TicketResponse third = issue(w);

        List<Recorder.Published> estimate = events(w.service(), "queue.estimate_changed");
        assertThat(estimate).hasSize(1);
        assertThat(estimate.getFirst().data()).containsEntry("waiting_count", 3).containsEntry("open_counters", 0);
        assertThat(estimateOf(estimate.getFirst())).isEqualTo(List.of(30, 35));

        // Only the ticket whose place is new is told; the two ahead of it stay where they were.
        List<Recorder.Published> moved = events(w.service(), "ticket.position_changed");
        assertThat(moved).hasSize(1);
        assertThat(moved.getFirst().data()).containsEntry("ticket_id", third.id().toString()).containsEntry("position", 3);
        assertThat(estimateOf(moved.getFirst())).isEqualTo(List.of(20, 25));
    }

    @Test
    void callingTheHeadPublishesAPositionChangeForEveryTicketBehindIt() throws Exception {
        World w = world();
        Person agent = user(Role.AGENT, w, true);
        UUID session = openSession(agent, counter(w, "Desk 1", w.service()));
        issue(w);
        TicketResponse second = issue(w);
        TicketResponse third = issue(w);
        recorder.published.clear();

        ok(call(post("/api/v1/sessions/" + session + "/next"), agent.token(), null));

        List<Recorder.Published> moved = events(w.service(), "ticket.position_changed");
        assertThat(moved).hasSize(2);
        assertThat(moved).extracting(e -> e.data().get("ticket_id")).containsExactlyInAnyOrder(second.id().toString(), third.id().toString());
        for (Recorder.Published event : moved) {
            boolean isSecond = second.id().toString().equals(event.data().get("ticket_id"));
            assertThat(event.data().get("position")).isEqualTo(isSecond ? 1 : 2);
            assertThat(estimateOf(event)).isEqualTo(isSecond ? List.of(0, 5) : List.of(10, 15));
        }
        List<Recorder.Published> estimate = events(w.service(), "queue.estimate_changed");
        assertThat(estimate).hasSize(1);
        assertThat(estimate.getFirst().data()).containsEntry("waiting_count", 2).containsEntry("open_counters", 1);
        assertThat(estimateOf(estimate.getFirst())).isEqualTo(List.of(20, 25));
    }

    @Test
    void completingATicketAddsASampleAndAStepThatMovesNothingPublishesNothing() throws Exception {
        World w = world();
        Person agent = user(Role.AGENT, w, true);
        UUID session = openSession(agent, counter(w, "Desk 1", w.service()));
        issue(w);
        ok(call(post("/api/v1/sessions/" + session + "/next"), agent.token(), null));
        recorder.published.clear();

        ok(call(post("/api/v1/sessions/" + session + "/serve"), agent.token(), null));
        assertThat(events(w.service(), "queue.estimate_changed")).isEmpty();
        assertThat(events(w.service(), "ticket.position_changed")).isEmpty();

        ok(call(post("/api/v1/sessions/" + session + "/complete"), agent.token(), null));
        assertThat(events(w.service(), "queue.estimate_changed")).hasSize(1);
    }

    @Test
    void openingAndClosingASessionRepublishesTheEstimateWithTheNewNumberOfOpenCounters() throws Exception {
        World w = world();
        Person agent = user(Role.AGENT, w, true);
        UUID desk = counter(w, "Desk 1", w.service());
        issue(w);
        issue(w);
        issue(w);
        recorder.published.clear();

        UUID session = openSession(agent, desk);
        List<Recorder.Published> opened = events(w.service(), "queue.estimate_changed");
        assertThat(opened).isNotEmpty();
        assertThat(opened.getLast().data()).containsEntry("open_counters", 1).containsEntry("waiting_count", 3);

        recorder.published.clear();
        ok(call(delete("/api/v1/sessions/" + session), agent.token(), null));
        List<Recorder.Published> closed = events(w.service(), "queue.estimate_changed");
        assertThat(closed).isNotEmpty();
        assertThat(closed.getLast().data()).containsEntry("open_counters", 0);
    }

    @Test
    void aTicketThatReturnsToTheQueueIsToldItsPlaceAgain() throws Exception {
        World w = world();
        Person agent = user(Role.AGENT, w, true);
        UUID session = openSession(agent, counter(w, "Desk 1", w.service()));
        TicketResponse first = issue(w);
        issue(w);
        ok(call(post("/api/v1/sessions/" + session + "/next"), agent.token(), null));
        ok(call(post("/api/v1/sessions/" + session + "/miss"), agent.token(), null));

        // Told at issue, silent while it was called, told again on coming back.
        long told = events(w.service(), "ticket.position_changed").stream().filter(e -> first.id().toString().equals(e.data().get("ticket_id"))).count();
        assertThat(told).isEqualTo(2);
    }
}
