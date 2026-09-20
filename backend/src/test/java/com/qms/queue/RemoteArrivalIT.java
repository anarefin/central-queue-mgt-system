package com.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.issuance.ActorType;
import com.qms.issuance.Channels;
import com.qms.issuance.IssuanceService;
import com.qms.issuance.IssueCommand;
import com.qms.issuance.TicketResponse;
import com.qms.platform.security.Role;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * Remote arrival, check-in and forfeit against real PostgreSQL (ticket 43, SRS §13.3, §19.1, FR-MOB-020..024,
 * FR-MOB-031, ADR-0004): a remote ticket is marked present by its own visitor (site QR or geofence) or by reception,
 * one delay per ticket if the Service allows it, and the background sweeps that notify an approaching turn and
 * forfeit a ticket still remote once its arrival deadline elapses at the front of its queue.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, RemoteArrivalIT.Clocks.class})
class RemoteArrivalIT {

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
        registry.add("qms.queue.remote-arrival-check-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-remote-arrival");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssuanceService issuance;
    @Autowired QueueReads queues;
    @Autowired RemoteArrivalService arrivals;
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

    private record World(UUID site, UUID group, UUID service) {}

    private World world() {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "R-" + site.toString().substring(0, 8));
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'G')", group, site);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, 'A', 10, 30, '[\"reception\",\"mobile\"]'::jsonb, 'both', true)",
                service, group);
        return new World(site, group, service);
    }

    /** A ticket issued normally, then flipped remote (mobile-origin), the same shortcut every other IT in this codebase takes. */
    private TicketResponse remoteTicket(UUID service) {
        TicketResponse issued = issuance.issue(new IssueCommand(service, Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null));
        jdbc.update("UPDATE ticket SET state = 'remote', origin_channel = 'mobile' WHERE id = ?", issued.id());
        return issued;
    }

    private UUID waitingTicket(UUID service) {
        return issuance.issue(new IssueCommand(service, Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null)).id();
    }

    private void remotePolicy(UUID service, int arrivalDeadlineMinutes, String forfeitPolicy, boolean delayAllowed) {
        jdbc.update(
                "INSERT INTO service_remote_rule (service_id, virtual_queue_enabled, join_window_minutes, arrival_deadline_minutes, forfeit_policy, delay_allowed)"
                        + " VALUES (?, true, 30, ?, ?, ?)"
                        + " ON CONFLICT (service_id) DO UPDATE SET arrival_deadline_minutes = EXCLUDED.arrival_deadline_minutes,"
                        + " forfeit_policy = EXCLUDED.forfeit_policy, delay_allowed = EXCLUDED.delay_allowed",
                service, arrivalDeadlineMinutes, forfeitPolicy, delayAllowed);
    }

    private void siteGeofence(UUID site, double latitude, double longitude, Integer radiusMeters) {
        jdbc.update(
                "INSERT INTO site_location (site_id, latitude, longitude, geofence_radius_m) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (site_id) DO UPDATE SET latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude, geofence_radius_m = EXCLUDED.geofence_radius_m",
                site, latitude, longitude, radiusMeters);
    }

    private record Staff(UUID id, String token) {}

    private Staff staff(Role role, UUID site) throws Exception {
        // Key signing/rotation is tied to real wall-clock time, not the test's own mutable clock (the same trick
        // TicketActionsIT#staff already uses): sign in under the real time, then restore the test clock.
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
            MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
            assertThat(status(login)).as(body(login)).isEqualTo(200);
            return new Staff(user, field(login, "$.access_token"));
        } finally {
            clock.set(testTime);
        }
    }

    private MvcResult checkIn(UUID id, String secret, String jsonBody) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/tickets/" + id + "/check-in").contentType(MediaType.APPLICATION_JSON);
        if (secret != null) request.header("X-Ticket-Secret", secret);
        if (jsonBody != null) request.content(jsonBody);
        return mvc.perform(request).andReturn();
    }

    private MvcResult delay(UUID id, String secret) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/tickets/" + id + "/delay");
        if (secret != null) request.header("X-Ticket-Secret", secret);
        return mvc.perform(request).andReturn();
    }

    private MvcResult receptionCheckIn(UUID id, String token) throws Exception {
        return mvc.perform(post("/api/v1/tickets/" + id + "/reception-check-in").header("Authorization", "Bearer " + token)).andReturn();
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

    private String state(UUID ticketId) {
        return jdbc.queryForObject("SELECT state FROM ticket WHERE id = ?", String.class, ticketId);
    }

    // ---- visitor check-in: geofence (FR-MOB-021, FR-MOB-024, §19.1) -----------------------------------------------

    @Test
    void aVisitorChecksInInsideTheGeofenceAndTheTicketMovesToWaiting() throws Exception {
        World w = world();
        TicketResponse ticket = remoteTicket(w.service());
        siteGeofence(w.site(), 23.8103, 90.4125, 200);

        MvcResult result = checkIn(ticket.id(), ticket.secret(), "{\"method\":\"geofence\",\"latitude\":23.8103,\"longitude\":90.4125}");
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat((String) field(result, "$.state")).isEqualTo("waiting");
        assertThat(state(ticket.id())).isEqualTo("waiting");

        var event = jdbc.queryForMap("SELECT event_type, from_state, to_state, actor_type, payload::text AS payload FROM ticket_event WHERE ticket_id = ? AND event_type = 'ticket.checked_in'", ticket.id());
        assertThat(event.get("from_state")).isEqualTo("remote");
        assertThat(event.get("to_state")).isEqualTo("waiting");
        assertThat(event.get("actor_type")).isEqualTo("visitor");
        assertThat((String) event.get("payload")).contains("geofence");

        Integer auditCount = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'ticket.checked_in'", Integer.class, ticket.id());
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void aVisitorOutsideTheGeofenceIsRefusedTooFar() throws Exception {
        World w = world();
        TicketResponse ticket = remoteTicket(w.service());
        siteGeofence(w.site(), 23.8103, 90.4125, 200);

        // Roughly 1 degree of latitude away: well over 100 km, far outside a 200 m radius.
        MvcResult result = checkIn(ticket.id(), ticket.secret(), "{\"method\":\"geofence\",\"latitude\":24.8103,\"longitude\":90.4125}");
        assertThat(status(result)).isEqualTo(409);
        assertThat((String) field(result, "$.error.details.reason")).isEqualTo("too_far");
        assertThat(state(ticket.id())).isEqualTo("remote");
    }

    @Test
    void geofenceCheckInIsRefusedWhenNoRadiusIsConfigured() throws Exception {
        World w = world();
        TicketResponse ticket = remoteTicket(w.service());

        MvcResult result = checkIn(ticket.id(), ticket.secret(), "{\"method\":\"geofence\",\"latitude\":23.8103,\"longitude\":90.4125}");
        assertThat(status(result)).isEqualTo(409);
        assertThat((String) field(result, "$.error.details.reason")).isEqualTo("geofence_not_configured");
    }

    @Test
    void geofenceCheckInWithoutCoordinatesIsValidationFailed() throws Exception {
        World w = world();
        TicketResponse ticket = remoteTicket(w.service());
        siteGeofence(w.site(), 23.8103, 90.4125, 200);

        MvcResult result = checkIn(ticket.id(), ticket.secret(), "{\"method\":\"geofence\"}");
        assertThat(status(result)).isEqualTo(400);
    }

    // ---- visitor check-in: QR, the drift fallback (FR-MOB-021, FR-MOB-024) ------------------------------------------

    @Test
    void aVisitorChecksInByQrEvenFarFromTheGeofence() throws Exception {
        World w = world();
        TicketResponse ticket = remoteTicket(w.service());
        siteGeofence(w.site(), 23.8103, 90.4125, 50);

        MvcResult result = checkIn(ticket.id(), ticket.secret(), "{\"method\":\"qr\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat(state(ticket.id())).isEqualTo("waiting");
    }

    @Test
    void anUnknownMethodIsValidationFailed() throws Exception {
        World w = world();
        TicketResponse ticket = remoteTicket(w.service());

        MvcResult result = checkIn(ticket.id(), ticket.secret(), "{\"method\":\"carrier_pigeon\"}");
        assertThat(status(result)).isEqualTo(400);
    }

    @Test
    void aNonRemoteTicketCannotBeCheckedInAndACheckedInTicketCannotBeCheckedInTwice() throws Exception {
        World w = world();
        TicketResponse waiting = issuance.issue(new IssueCommand(w.service(), Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null));
        MvcResult refused = checkIn(waiting.id(), waiting.secret(), "{\"method\":\"qr\"}");
        assertThat(status(refused)).isEqualTo(409);
        assertThat((String) field(refused, "$.error.details.reason")).isEqualTo("ticket_not_remote");

        TicketResponse ticket = remoteTicket(w.service());
        assertThat(status(checkIn(ticket.id(), ticket.secret(), "{\"method\":\"qr\"}"))).isEqualTo(200);
        MvcResult again = checkIn(ticket.id(), ticket.secret(), "{\"method\":\"qr\"}");
        assertThat(status(again)).isEqualTo(409);
        assertThat((String) field(again, "$.error.details.reason")).isEqualTo("ticket_not_remote");
    }

    // ---- reception check-in (FR-MOB-021, §5.2) -----------------------------------------------------------------

    @Test
    void receptionChecksInARemoteTicketOnTheVisitorsBehalf() throws Exception {
        World w = world();
        TicketResponse ticket = remoteTicket(w.service());
        Staff reception = staff(Role.RECEPTION_OPERATOR, w.site());

        MvcResult result = receptionCheckIn(ticket.id(), reception.token());
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat((String) field(result, "$.state")).isEqualTo("waiting");

        var event = jdbc.queryForMap("SELECT actor_type, actor_id, payload::text AS payload FROM ticket_event WHERE ticket_id = ? AND event_type = 'ticket.checked_in'", ticket.id());
        assertThat(event.get("actor_type")).isEqualTo("staff");
        assertThat(event.get("actor_id")).isEqualTo(reception.id());
        assertThat((String) event.get("payload")).contains("reception");
    }

    @Test
    void anAgentMayNotReceptionCheckInATicket() throws Exception {
        World w = world();
        TicketResponse ticket = remoteTicket(w.service());
        Staff agent = staff(Role.AGENT, w.site());

        assertThat(status(receptionCheckIn(ticket.id(), agent.token()))).isEqualTo(403);
        assertThat(state(ticket.id())).isEqualTo("remote");
    }

    // ---- delay: one per ticket, if the Service allows it (FR-MOB-031, ADR-0004) -------------------------------------

    @Test
    void aVisitorDelaysTheirRemoteTicketOnceMovingItBack() throws Exception {
        World w = world();
        remotePolicy(w.service(), 15, "move_back", true);
        waitingTicket(w.service());
        waitingTicket(w.service());
        TicketResponse ticket = remoteTicket(w.service());

        MvcResult result = delay(ticket.id(), ticket.secret());
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat((String) field(result, "$.state")).isEqualTo("remote");

        Boolean delayUsed = jdbc.queryForObject("SELECT delay_used FROM ticket WHERE id = ?", Boolean.class, ticket.id());
        assertThat(delayUsed).isTrue();
        Integer adjustment = jdbc.queryForObject("SELECT score_adjustment_minutes FROM ticket WHERE id = ?", Integer.class, ticket.id());
        assertThat(adjustment).as("moved back, so a negative (or zero, with too little of a queue to move behind) adjustment").isLessThanOrEqualTo(0);

        MvcResult again = delay(ticket.id(), ticket.secret());
        assertThat(status(again)).isEqualTo(409);
        assertThat((String) field(again, "$.error.details.reason")).isEqualTo("delay_already_used");
    }

    @Test
    void aDelayIsRefusedWhenTheServiceDoesNotAllowIt() throws Exception {
        World w = world();
        remotePolicy(w.service(), 15, "move_back", false);
        TicketResponse ticket = remoteTicket(w.service());

        MvcResult result = delay(ticket.id(), ticket.secret());
        assertThat(status(result)).isEqualTo(409);
        assertThat((String) field(result, "$.error.details.reason")).isEqualTo("delay_not_allowed");
    }

    @Test
    void aDelayIsRefusedWithNoRemoteRuleAtAllBecauseDelayDefaultsOff() throws Exception {
        World w = world();
        TicketResponse ticket = remoteTicket(w.service());

        MvcResult result = delay(ticket.id(), ticket.secret());
        assertThat(status(result)).isEqualTo(409);
        assertThat((String) field(result, "$.error.details.reason")).isEqualTo("delay_not_allowed");
    }

    @Test
    void aNonRemoteTicketCannotBeDelayed() throws Exception {
        World w = world();
        remotePolicy(w.service(), 15, "move_back", true);
        TicketResponse waiting = issuance.issue(new IssueCommand(w.service(), Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null));

        MvcResult result = delay(waiting.id(), waiting.secret());
        assertThat(status(result)).isEqualTo(409);
        assertThat((String) field(result, "$.error.details.reason")).isEqualTo("ticket_not_remote");
    }

    // ---- forfeit sweep (FR-MOB-022, ADR-0004) ------------------------------------------------------------------

    @Test
    void aRemoteTicketAtTheFrontStartsItsHoldClockThenIsCancelledOnceTheDeadlineElapses() {
        World w = world();
        remotePolicy(w.service(), 15, "cancel", false);
        TicketResponse ticket = remoteTicket(w.service());

        arrivals.sweepForfeits(); // first tick just starts the hold clock
        Instant heldSince = jdbc.query(
                        "SELECT remote_hold_started_at FROM ticket WHERE id = ?", (rs, i) -> rs.getObject(1, java.time.OffsetDateTime.class), ticket.id())
                .stream().findFirst().orElseThrow().toInstant();
        assertThat(heldSince).isEqualTo(BASE);
        assertThat(state(ticket.id())).isEqualTo("remote");

        clock.advance(Duration.ofMinutes(16));
        arrivals.sweepForfeits();
        assertThat(state(ticket.id())).isEqualTo("forfeited");

        var event = jdbc.queryForMap("SELECT from_state, to_state, actor_type FROM ticket_event WHERE ticket_id = ? AND event_type = 'ticket.forfeited'", ticket.id());
        assertThat(event.get("from_state")).isEqualTo("remote");
        assertThat(event.get("to_state")).isEqualTo("forfeited");
        assertThat(event.get("actor_type")).isEqualTo("system");
        Integer auditCount = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'ticket.forfeited'", Integer.class, ticket.id());
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void aRemoteTicketAtTheFrontIsMovedBackInsteadWhenThePolicyIsMoveBack() {
        World w = world();
        remotePolicy(w.service(), 15, "move_back", false);
        // Queued before the two others waiting, so it unambiguously ranks at the front (ADR-0003) for them to move
        // back behind.
        TicketResponse ticket = remoteTicket(w.service());
        clock.set(BASE.plusSeconds(60));
        waitingTicket(w.service());
        waitingTicket(w.service());
        clock.set(BASE);

        arrivals.sweepForfeits();
        clock.advance(Duration.ofMinutes(16));
        arrivals.sweepForfeits();

        assertThat(state(ticket.id())).as("still remote: another chance, not closed").isEqualTo("remote");
        Boolean stillHeld = jdbc.query("SELECT remote_hold_started_at FROM ticket WHERE id = ?", (rs, i) -> rs.getObject(1) != null, ticket.id()).stream().findFirst().orElseThrow();
        assertThat(stillHeld).as("the hold clock is cleared; it starts again once it next reaches the front").isFalse();

        var event = jdbc.queryForMap(
                "SELECT from_state, to_state, payload::text AS payload FROM ticket_event WHERE ticket_id = ? AND event_type = 'ticket.forfeit_moved_back'", ticket.id());
        assertThat(event.get("from_state")).isEqualTo("remote");
        assertThat(event.get("to_state")).isEqualTo("remote");
        assertThat((String) event.get("payload")).contains("score_adjustment_minutes");
    }

    @Test
    void aRemoteTicketThatFallsOffTheFrontLosesItsHoldClockAndGetsNoForfeit() {
        World w = world();
        remotePolicy(w.service(), 15, "cancel", false);
        TicketResponse ticket = remoteTicket(w.service());
        arrivals.sweepForfeits(); // starts the hold clock

        // A ticket that has waited longer scores higher under weighted_wait (ADR-0003) and overtakes it at the front.
        UUID overtaker = waitingTicket(w.service());
        jdbc.update("UPDATE ticket SET queued_at = ? WHERE id = ?", java.sql.Timestamp.from(BASE.minusSeconds(3600)), overtaker);

        clock.advance(Duration.ofMinutes(16));
        arrivals.sweepForfeits();
        assertThat(state(ticket.id())).as("the overtaker, not the remote ticket, was at the front: no forfeit").isEqualTo("remote");
        Boolean stillHeld = jdbc.query("SELECT remote_hold_started_at FROM ticket WHERE id = ?", (rs, i) -> rs.getObject(1) != null, ticket.id()).stream().findFirst().orElseThrow();
        assertThat(stillHeld).as("no longer at the front, so its clock was cleared").isFalse();
    }

    // ---- approaching-turn notification (FR-MOB-020) ------------------------------------------------------------

    @Test
    void anApproachingRemoteTicketIsNotifiedOnceNotTwice() {
        World w = world();
        TicketResponse ticket = remoteTicket(w.service());
        jdbc.update(
                "INSERT INTO notification_template (id, trigger_key, channel, language, subject, body) VALUES (?, 'approaching_turn', 'web_push', 'en', 'Almost there', 'Get ready, {{token_number}}')"
                        + " ON CONFLICT (trigger_key, channel, language) DO UPDATE SET body = EXCLUDED.body",
                UUID.randomUUID());

        arrivals.sweepApproachingTurn(); // alone at the front, well inside the default threshold
        Instant notifiedAt = jdbc.query(
                        "SELECT approaching_turn_notified_at FROM ticket WHERE id = ?", (rs, i) -> rs.getObject(1, java.time.OffsetDateTime.class), ticket.id())
                .stream().findFirst().orElseThrow().toInstant();
        assertThat(notifiedAt).isNotNull();

        Integer messages = jdbc.queryForObject(
                "SELECT count(*) FROM notification_message WHERE ticket_id = ? AND trigger_key = 'approaching_turn'", Integer.class, ticket.id());
        assertThat(messages).isEqualTo(1);

        arrivals.sweepApproachingTurn(); // already notified: nothing more to do
        Integer stillOne = jdbc.queryForObject(
                "SELECT count(*) FROM notification_message WHERE ticket_id = ? AND trigger_key = 'approaching_turn'", Integer.class, ticket.id());
        assertThat(stillOne).isEqualTo(1);
    }

    @Test
    void aRemoteTicketFarFromItsTurnIsNotYetNotified() {
        World w = world();
        // Queued well before the remote ticket, so they deterministically outrank it (earlier wait scores higher,
        // ADR-0003) rather than tying on the same instant and breaking by id.
        clock.set(BASE.minusSeconds(3600));
        for (int i = 0; i < 5; i++) waitingTicket(w.service());
        clock.set(BASE);
        TicketResponse ticket = remoteTicket(w.service());

        arrivals.sweepApproachingTurn(); // 5 tickets ahead, past the default threshold of 3
        Boolean notified = jdbc.query(
                        "SELECT approaching_turn_notified_at FROM ticket WHERE id = ?", (rs, i) -> rs.getObject(1) != null, ticket.id())
                .stream().findFirst().orElseThrow();
        assertThat(notified).isFalse();
    }
}
