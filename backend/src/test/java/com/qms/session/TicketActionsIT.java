package com.qms.session;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Ticket 18 against real PostgreSQL with a clock the test moves: a new ticket's Priority class comes from the first source that
 * names one (FR-QUE-011), a default that changes later never moves an issued ticket (FR-CFG-041), staff change a waiting
 * ticket's class with a reason and the queue reflects it at once (FR-QUE-012, FR-SEC-040, UAT U9), and staff cancel active tickets,
 * an agent only their own (§5.2, §19.1, NFR-MNT-004).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, TicketActionsIT.Clocks.class})
class TicketActionsIT {

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
        registry.add("qms.queue.call-timeout-check-cron", () -> "-");
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-ticket-actions");
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
        // Channel defaults are organisation-wide and shared by every test in the database.
        jdbc.update("DELETE FROM channel_priority_default");
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private record World(UUID site, UUID zone, UUID group, UUID service) {}

    private record Staff(UUID id, String token) {}

    private World world() {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "T-" + site.toString().substring(0, 8));
        UUID zone = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, floor_label) VALUES (?, ?, 'Hall', '1st')", zone, site);
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'G')", group, site);
        jdbc.update("INSERT INTO team (id, service_group_id, name) VALUES (?, ?, 'Outpatient team')", UUID.randomUUID(), group);
        return new World(site, zone, group, newService(group, "A"));
    }

    private UUID newService(UUID group, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, ?, 10, 30, '[\"reception\",\"kiosk\"]'::jsonb, 'both')",
                id, group, prefix);
        return id;
    }

    private UUID counter(World w, String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, ?)", id, w.zone(), label);
        jdbc.update("INSERT INTO counter_service (counter_id, service_id, preference_weight) VALUES (?, ?, 1)", id, w.service());
        return id;
    }

    private UUID newClass(String name, int headstart) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO priority_class (id, name_i18n, headstart_minutes, created_at, updated_at) VALUES (?, ?::jsonb, ?, now(), now())", id, "{\"en\":\"" + name + "\"}", headstart);
        return id;
    }

    private UUID defaultClass() {
        return jdbc.queryForObject("SELECT id FROM priority_class WHERE is_default", UUID.class);
    }

    private Staff staff(Role role, UUID site, UUID teamOf) throws Exception {
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
            if (teamOf != null) jdbc.update("INSERT INTO team_member (team_id, user_id) SELECT id, ? FROM team WHERE service_group_id = ?", user, teamOf);
            MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn();
            assertThat(status(result)).as(body(result)).isEqualTo(200);
            return new Staff(user, JsonPath.read(body(result), "$.access_token"));
        } finally {
            clock.set(testTime);
        }
    }

    /** Issues a ticket as if {@code minutesAgo} minutes before BASE, then puts the clock back at BASE. */
    private UUID issueAgo(UUID service, int minutesAgo) {
        return issueAgo(service, minutesAgo, Channels.RECEPTION, null);
    }

    private UUID issueAgo(UUID service, int minutesAgo, String channel, UUID manualClass) {
        clock.set(BASE.minus(Duration.ofMinutes(minutesAgo)));
        try {
            return issuance.issue(new IssueCommand(service, channel, UUID.randomUUID(), ActorType.SYSTEM, null, manualClass)).id();
        } finally {
            clock.set(BASE);
        }
    }

    private UUID classOf(UUID ticket) {
        UUID stored = jdbc.queryForObject("SELECT priority_class_id FROM ticket WHERE id = ?", UUID.class, ticket);
        return stored == null ? defaultClass() : stored;
    }

    private List<UUID> order(UUID service) {
        return queues.ordered(service, null).entries().stream().map(QueueReads.Entry::ticketId).toList();
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andReturn();
    }

    private MvcResult reprioritise(Staff who, UUID ticket, UUID toClass, String reason) throws Exception {
        String json = "{" + (toClass == null ? "" : "\"priority_class_id\":\"" + toClass + "\"") + (reason == null ? "" : (toClass == null ? "" : ",") + "\"reason\":\"" + reason + "\"") + "}";
        return call(post("/api/v1/tickets/" + ticket + "/priority"), who.token(), json);
    }

    private MvcResult cancel(Staff who, UUID ticket, String reason) throws Exception {
        return call(post("/api/v1/tickets/" + ticket + "/cancel"), who.token(), reason == null ? null : "{\"reason\":\"" + reason + "\"}");
    }

    private void setChannelDefault(String channel, UUID classId) {
        jdbc.update("DELETE FROM channel_priority_default WHERE channel = ?", channel);
        if (classId != null) jdbc.update("INSERT INTO channel_priority_default (channel, priority_class_id, updated_at) VALUES (?, ?, now())", channel, classId);
    }

    private void setServiceDefault(UUID service, UUID classId) {
        jdbc.update("UPDATE service SET default_priority_class_id = ? WHERE id = ?", classId, service);
    }

    private List<Map<String, Object>> eventsOf(UUID ticket) {
        return jdbc.queryForList("SELECT seq, event_type, from_state, to_state, counter_id, actor_id, payload::text AS payload FROM ticket_event WHERE ticket_id = ? ORDER BY seq", ticket);
    }

    private Map<String, Object> audit(String action, UUID entityId) {
        return jdbc.queryForMap("SELECT actor_id, reason, before::text AS before, after::text AS after FROM audit_log WHERE action = ? AND entity_id = ? ORDER BY occurred_at DESC LIMIT 1", action, entityId);
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

    // ---- FR-QUE-011: where a new ticket's class comes from ------------------------------------------------------------

    @Test
    void aNewTicketTakesItsClassFromTheFirstSourceThatNamesOne() {
        World w = world();
        UUID staffChoice = newClass("Staff choice", 10);
        UUID channelClass = newClass("Channel class", 20);
        UUID serviceClass = newClass("Service class", 30);

        assertThat(classOf(issueAgo(w.service(), 1))).as("no source names a class: the normal class").isEqualTo(defaultClass());

        setServiceDefault(w.service(), serviceClass);
        assertThat(classOf(issueAgo(w.service(), 1))).as("the service default").isEqualTo(serviceClass);

        setChannelDefault(Channels.RECEPTION, channelClass);
        assertThat(classOf(issueAgo(w.service(), 1))).as("the channel default beats the service default").isEqualTo(channelClass);
        assertThat(classOf(issueAgo(w.service(), 1, Channels.KIOSK, null))).as("another channel has no default of its own").isEqualTo(serviceClass);

        UUID manual = issueAgo(w.service(), 1, Channels.RECEPTION, staffChoice);
        assertThat(classOf(manual)).as("staff's choice beats every default").isEqualTo(staffChoice);
        assertThat(inPayload(eventsOf(manual).getFirst(), "$.priority_source")).isEqualTo("manual");
        UUID viaChannel = issueAgo(w.service(), 1);
        assertThat(inPayload(eventsOf(viaChannel).getFirst(), "$.priority_source")).isEqualTo("channel_default");
    }

    @Test
    void aDefaultWhoseClassHasBeenSwitchedOffIsPassedOver() {
        World w = world();
        UUID channelClass = newClass("Channel class", 20);
        UUID serviceClass = newClass("Service class", 30);
        setChannelDefault(Channels.RECEPTION, channelClass);
        setServiceDefault(w.service(), serviceClass);

        jdbc.update("UPDATE priority_class SET active = false WHERE id = ?", channelClass);
        assertThat(classOf(issueAgo(w.service(), 1))).as("the next source answers").isEqualTo(serviceClass);
        jdbc.update("UPDATE priority_class SET active = false WHERE id = ?", serviceClass);
        assertThat(classOf(issueAgo(w.service(), 1))).isEqualTo(defaultClass());
    }

    @Test
    void changingADefaultNeverMovesATicketThatWasAlreadyIssued() throws Exception {
        World w = world();
        UUID first = newClass("First", 20);
        UUID second = newClass("Second", 40);
        setServiceDefault(w.service(), first);
        UUID before = issueAgo(w.service(), 5);
        UUID plain = issueAgo(w.service(), 4, Channels.KIOSK, null);
        assertThat(classOf(before)).isEqualTo(first);

        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);
        assertThat(status(call(put("/api/v1/priority-defaults/services/" + w.service()), admin.token(), "{\"priority_class_id\":\"" + second + "\"}"))).isEqualTo(200);
        assertThat(status(call(put("/api/v1/priority-defaults/channels/reception"), admin.token(), "{\"priority_class_id\":\"" + second + "\"}"))).isEqualTo(200);
        assertThat(status(call(put("/api/v1/priority-defaults/services/" + w.service()), admin.token(), "{\"priority_class_id\":null}"))).isEqualTo(200);

        assertThat(classOf(before)).as("issued before the change: untouched").isEqualTo(first);
        assertThat(classOf(plain)).isEqualTo(first);
        assertThat(classOf(issueAgo(w.service(), 1))).as("issued after: the new default").isEqualTo(second);
    }

    // ---- the defaults themselves: who sets them and what is refused --------------------------------------------------

    @Test
    void defaultsAreSetOnTheServerAuditedAndCheckedForTheClassAndThePermission() throws Exception {
        World w = world();
        UUID vip = newClass("VIP", 30);
        Staff admin = staff(Role.ORG_ADMIN, w.site(), null);

        MvcResult set = call(put("/api/v1/priority-defaults/channels/kiosk"), admin.token(), "{\"priority_class_id\":\"" + vip + "\"}");
        assertThat(status(set)).as(body(set)).isEqualTo(200);
        assertThat((String) field(set, "$.priority_class_id")).isEqualTo(vip.toString());
        assertThat(jdbc.queryForObject("SELECT priority_class_id FROM channel_priority_default WHERE channel = 'kiosk'", UUID.class)).isEqualTo(vip);
        MvcResult service = call(put("/api/v1/priority-defaults/services/" + w.service()), admin.token(), "{\"priority_class_id\":\"" + vip + "\"}");
        assertThat(status(service)).as(body(service)).isEqualTo(200);

        MvcResult listed = call(get("/api/v1/priority-defaults"), admin.token(), null);
        assertThat(status(listed)).isEqualTo(200);
        assertThat((List<String>) field(listed, "$.channels[*].channel")).containsExactly("kiosk", "reception", "mobile", "appointment_checkin");
        assertThat((List<String>) field(listed, "$.channels[?(@.channel=='kiosk')].priority_class_id")).containsExactly(vip.toString());
        assertThat((List<String>) field(listed, "$.services[?(@.service_id=='" + w.service() + "')].priority_class_id")).containsExactly(vip.toString());

        Map<String, Object> entry = jdbc.queryForMap("SELECT before::text AS before, after::text AS after FROM audit_log WHERE action = 'priority_default.service_updated' AND entity_id = ?", w.service());
        assertThat(JsonPath.<Object>read((String) entry.get("before"), "$.priority_class_id")).isNull();
        assertThat(JsonPath.<String>read((String) entry.get("after"), "$.priority_class_id")).isEqualTo(vip.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'priority_default.channel_updated'", Integer.class)).isPositive();

        assertThat(status(call(put("/api/v1/priority-defaults/channels/kiosk"), admin.token(), "{\"priority_class_id\":\"" + UUID.randomUUID() + "\"}"))).as("no such class").isEqualTo(400);
        UUID off = newClass("Off", 5);
        jdbc.update("UPDATE priority_class SET active = false WHERE id = ?", off);
        assertThat(status(call(put("/api/v1/priority-defaults/services/" + w.service()), admin.token(), "{\"priority_class_id\":\"" + off + "\"}"))).as("switched off").isEqualTo(400);
        assertThat(status(call(put("/api/v1/priority-defaults/channels/fax"), admin.token(), "{\"priority_class_id\":\"" + vip + "\"}"))).as("no such channel").isEqualTo(404);
        assertThat(status(call(put("/api/v1/priority-defaults/services/" + UUID.randomUUID()), admin.token(), "{\"priority_class_id\":null}"))).isEqualTo(404);

        Staff reception = staff(Role.RECEPTION_OPERATOR, w.site(), null);
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        for (Staff denied : List.of(reception, agent)) {
            assertThat(status(call(put("/api/v1/priority-defaults/channels/kiosk"), denied.token(), "{\"priority_class_id\":null}"))).isEqualTo(403);
            assertThat(status(call(get("/api/v1/priority-defaults"), denied.token(), null))).isEqualTo(403);
        }
        World other = world();
        Staff scoped = staff(Role.ORG_ADMIN, other.site(), null);
        assertThat(status(call(put("/api/v1/priority-defaults/services/" + w.service()), scoped.token(), "{\"priority_class_id\":null}"))).as("another site's service").isEqualTo(403);
    }

    // ---- FR-QUE-012, FR-SEC-040, UAT U9: change a waiting ticket's class ----------------------------------------------

    @Test
    void aSupervisorMovesAWaitingTicketAheadAndTheQueueShowsItAtOnceWithTheReasonInTheAuditLog() throws Exception {
        World w = world();
        UUID emergency = newClass("Emergency", 60);
        UUID oldest = issueAgo(w.service(), 10);
        UUID middle = issueAgo(w.service(), 6);
        UUID newest = issueAgo(w.service(), 2);
        assertThat(order(w.service())).containsExactly(oldest, middle, newest);
        Staff supervisor = staff(Role.TEAM_ADMIN, w.site(), null);

        MvcResult changed = reprioritise(supervisor, newest, emergency, "Patient in distress");
        assertThat(status(changed)).as(body(changed)).isEqualTo(200);
        assertThat((String) field(changed, "$.state")).isEqualTo("waiting");
        assertThat((String) field(changed, "$.priority_class_id")).isEqualTo(emergency.toString());
        assertThat((Integer) field(changed, "$.position")).as("60 minutes of head start passes both").isEqualTo(1);

        // Order is computed on read: the very next read, on the same clock, already has the new order (U9: within 5 s).
        assertThat(order(w.service())).containsExactly(newest, oldest, middle);
        MvcResult snapshot = call(get("/api/v1/queues/" + w.service()), supervisor.token(), null);
        assertThat((List<Integer>) field(snapshot, "$.tickets[*].position")).containsExactly(1, 2, 3);
        assertThat((String) field(snapshot, "$.tickets[0].priority_class.name_i18n.en")).isEqualTo("Emergency");
        assertThat(classOf(newest)).isEqualTo(emergency);

        Map<String, Object> audited = audit("ticket.priority_changed", newest);
        assertThat(audited.get("reason")).isEqualTo("Patient in distress");
        assertThat(audited.get("actor_id")).isEqualTo(supervisor.id());
        assertThat(JsonPath.<String>read((String) audited.get("before"), "$.priority_class_id")).isEqualTo(defaultClass().toString());
        assertThat(JsonPath.<String>read((String) audited.get("after"), "$.priority_class_id")).isEqualTo(emergency.toString());

        List<Map<String, Object>> events = eventsOf(newest);
        assertThat(events).extracting(e -> e.get("event_type")).containsExactly("ticket.issued", "ticket.position_changed");
        Map<String, Object> event = events.get(1);
        assertThat(event.get("from_state")).isEqualTo("waiting");
        assertThat(event.get("to_state")).isEqualTo("waiting");
        assertThat(event.get("actor_id")).isEqualTo(supervisor.id());
        assertThat(inPayload(event, "$.priority_class_id")).isEqualTo(emergency.toString());
        assertThat(inPayload(event, "$.previous_priority_class_id")).isEqualTo(defaultClass().toString());
    }

    @Test
    void receptionCanChangeTheClassBackToNormalAndTheDefaultClassIsStoredAsNoClass() throws Exception {
        World w = world();
        UUID emergency = newClass("Emergency", 60);
        UUID ticket = issueAgo(w.service(), 1, Channels.RECEPTION, emergency);
        Staff reception = staff(Role.RECEPTION_OPERATOR, w.site(), null);

        MvcResult back = reprioritise(reception, ticket, defaultClass(), "Visitor asked to wait their turn");
        assertThat(status(back)).as(body(back)).isEqualTo(200);
        assertThat((String) field(back, "$.priority_class_id")).isEqualTo(defaultClass().toString());
        assertThat(jdbc.queryForObject("SELECT priority_class_id FROM ticket WHERE id = ?", UUID.class, ticket)).isNull();
    }

    @Test
    void aChangeNeedsAReasonAnActiveClassAndAWaitingTicketThatIsNotAlreadyInThatClass() throws Exception {
        World w = world();
        counter(w, "1");
        UUID emergency = newClass("Emergency", 60);
        UUID off = newClass("Off", 5);
        jdbc.update("UPDATE priority_class SET active = false WHERE id = ?", off);
        UUID ticket = issueAgo(w.service(), 3);
        Staff supervisor = staff(Role.TEAM_ADMIN, w.site(), null);

        assertThat(status(reprioritise(supervisor, ticket, emergency, null))).as("the reason is mandatory").isEqualTo(400);
        assertThat(status(reprioritise(supervisor, ticket, emergency, "  "))).as("a blank reason is none").isEqualTo(400);
        assertThat(status(reprioritise(supervisor, ticket, null, "why"))).as("a class is needed").isEqualTo(400);
        assertThat(status(reprioritise(supervisor, ticket, UUID.randomUUID(), "why"))).isEqualTo(400);
        assertThat(status(reprioritise(supervisor, ticket, off, "why"))).as("a switched-off class").isEqualTo(400);
        assertThat(status(reprioritise(supervisor, UUID.randomUUID(), emergency, "why"))).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ticket.priority_changed' AND entity_id = ?", Integer.class, ticket)).as("nothing changed, nothing audited").isZero();

        MvcResult same = reprioritise(supervisor, ticket, defaultClass(), "why");
        assertThat(status(same)).isEqualTo(409);
        assertThat(reason(same)).isEqualTo("same_priority_class");

        MvcResult stale = call(post("/api/v1/tickets/" + ticket + "/priority").header("If-Match", "\"7\""), supervisor.token(), "{\"priority_class_id\":\"" + emergency + "\",\"reason\":\"why\"}");
        assertThat(status(stale)).isEqualTo(409);
        assertThat(reason(stale)).isEqualTo("version_mismatch");

        // Once a ticket has been called it has left the queue: there is no place to move it to.
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        UUID session = openSession(agent, counter(w, "2"));
        assertThat(status(call(post("/api/v1/sessions/" + session + "/next"), agent.token(), null))).isEqualTo(200);
        MvcResult called = reprioritise(supervisor, ticket, emergency, "why");
        assertThat(status(called)).isEqualTo(409);
        assertThat(reason(called)).isEqualTo("ticket_not_waiting");
    }

    @Test
    void aChangeIsCheckedOnTheServerForThePermissionAndTheSite() throws Exception {
        World w = world();
        UUID emergency = newClass("Emergency", 60);
        UUID ticket = issueAgo(w.service(), 3);
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        assertThat(status(reprioritise(agent, ticket, emergency, "why"))).as("Agents may not re-prioritise (§5.2)").isEqualTo(403);
        assertThat(status(call(post("/api/v1/tickets/" + ticket + "/priority"), null, "{}"))).isEqualTo(401);

        World other = world();
        Staff elsewhere = staff(Role.TEAM_ADMIN, other.site(), null);
        assertThat(status(reprioritise(elsewhere, ticket, emergency, "why"))).as("another site's ticket").isEqualTo(403);
        assertThat(classOf(ticket)).isEqualTo(defaultClass());
    }

    // ---- §19.1, §5.2: cancel ------------------------------------------------------------------------------------------

    @Test
    void receptionCancelsAWaitingTicketWhichLeavesTheQueueWithItsWaitStoredAndOneEvent() throws Exception {
        World w = world();
        UUID first = issueAgo(w.service(), 10);
        UUID second = issueAgo(w.service(), 5);
        Staff reception = staff(Role.RECEPTION_OPERATOR, w.site(), null);

        MvcResult cancelled = cancel(reception, first, "Visitor left");
        assertThat(status(cancelled)).as(body(cancelled)).isEqualTo(200);
        assertThat((String) field(cancelled, "$.state")).isEqualTo("cancelled");
        assertThat((Object) field(cancelled, "$.position")).isNull();

        assertThat(order(w.service())).as("the cancelled ticket leaves the queue").containsExactly(second);
        Map<String, Object> row = jdbc.queryForMap("SELECT state, counter_session_id, wait_seconds, service_seconds, closed_at FROM ticket WHERE id = ?", first);
        assertThat(row.get("state")).isEqualTo("cancelled");
        assertThat(row.get("wait_seconds")).as("ten minutes of waiting").isEqualTo(600);
        assertThat(row.get("service_seconds")).isNull();
        assertThat(row.get("closed_at")).isNotNull();

        List<Map<String, Object>> events = eventsOf(first);
        assertThat(events).extracting(e -> e.get("event_type")).containsExactly("ticket.issued", "ticket.cancelled");
        assertThat(events.get(1).get("from_state")).isEqualTo("waiting");
        assertThat(events.get(1).get("to_state")).isEqualTo("cancelled");
        assertThat(events.get(1).get("actor_id")).isEqualTo(reception.id());
        assertThat(audit("ticket.cancelled", first).get("reason")).isEqualTo("Visitor left");

        MvcResult again = cancel(reception, first, null);
        assertThat(status(again)).isEqualTo(409);
        assertThat(reason(again)).isEqualTo("ticket_not_active");
        assertThat(eventsOf(first)).as("a second cancel writes nothing").hasSize(2);
    }

    @Test
    void aCancelIsAllowedFromEveryActiveStateAndAReasonIsOptional() throws Exception {
        World w = world();
        Staff admin = staff(Role.TEAM_ADMIN, w.site(), null);
        for (String state : List.of("remote", "waiting", "paused")) {
            UUID ticket = issueAgo(w.service(), 2);
            jdbc.update("UPDATE ticket SET state = ? WHERE id = ?", state, ticket);
            MvcResult cancelled = cancel(admin, ticket, null);
            assertThat(status(cancelled)).as(state + ": " + body(cancelled)).isEqualTo(200);
            assertThat(audit("ticket.cancelled", ticket).get("reason")).isNull();
        }
        for (String state : List.of("completed", "transferred", "no_show", "cancelled", "forfeited")) {
            UUID ticket = issueAgo(w.service(), 2);
            jdbc.update("UPDATE ticket SET state = ? WHERE id = ?", state, ticket);
            MvcResult refused = cancel(admin, ticket, null);
            assertThat(status(refused)).as(state).isEqualTo(409);
            assertThat(reason(refused)).isEqualTo("ticket_not_active");
        }
    }

    @Test
    void cancellingATicketInServiceClearsItsBindingStoresBothDurationsAndLeavesTheAgentFree() throws Exception {
        World w = world();
        UUID counter = counter(w, "1");
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        Staff supervisor = staff(Role.TEAM_ADMIN, w.site(), null);
        UUID ticket = issueAgo(w.service(), 4);
        UUID session = openSession(agent, counter);
        assertThat(status(call(post("/api/v1/sessions/" + session + "/next"), agent.token(), null))).isEqualTo(200);
        clock.set(BASE.plusSeconds(30));
        assertThat(status(call(post("/api/v1/sessions/" + session + "/serve"), agent.token(), null))).isEqualTo(200);
        clock.set(BASE.plusSeconds(150));

        MvcResult cancelled = cancel(supervisor, ticket, "Wrong desk, visitor left");
        assertThat(status(cancelled)).as(body(cancelled)).isEqualTo(200);

        Map<String, Object> row = jdbc.queryForMap("SELECT state, counter_session_id, wait_seconds, service_seconds FROM ticket WHERE id = ?", ticket);
        assertThat(row.get("state")).isEqualTo("cancelled");
        assertThat(row.get("counter_session_id")).as("Invariant 2: a terminal state clears the binding").isNull();
        assertThat(row.get("wait_seconds")).as("waited only while waiting, until the call").isEqualTo(4 * 60);
        assertThat(row.get("service_seconds")).isEqualTo(120);
        Map<String, Object> event = eventsOf(ticket).getLast();
        assertThat(event.get("event_type")).isEqualTo("ticket.cancelled");
        assertThat(event.get("from_state")).isEqualTo("serving");
        assertThat(event.get("counter_id")).as("the counter's console is told").isEqualTo(counter);

        MvcResult current = call(get("/api/v1/sessions/current"), agent.token(), null);
        assertThat((Object) field(current, "$.ticket")).as("the agent has nothing in progress").isNull();
        UUID next = issueAgo(w.service(), 1);
        MvcResult called = call(post("/api/v1/sessions/" + session + "/next"), agent.token(), null);
        assertThat(status(called)).as("the counter is free to call again").isEqualTo(200);
        assertThat((String) field(called, "$.ticket.id")).isEqualTo(next.toString());
    }

    @Test
    void cancellingTheTicketAClosingSessionWaitsOnClosesTheSession() throws Exception {
        World w = world();
        UUID counter = counter(w, "1");
        Staff agent = staff(Role.AGENT, w.site(), w.group());
        Staff supervisor = staff(Role.TEAM_ADMIN, w.site(), null);
        UUID ticket = issueAgo(w.service(), 2);
        UUID session = openSession(agent, counter);
        assertThat(status(call(post("/api/v1/sessions/" + session + "/next"), agent.token(), null))).isEqualTo(200);
        MvcResult closing = call(delete("/api/v1/sessions/" + session), agent.token(), null);
        assertThat(status(closing)).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT state FROM counter_session WHERE id = ?", String.class, session)).isEqualTo("closing");

        assertThat(status(cancel(supervisor, ticket, null))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT state FROM counter_session WHERE id = ?", String.class, session)).as("nothing is left to resolve").isEqualTo("closed");
    }

    @Test
    void anAgentCancelsOnlyTheirOwnTickets() throws Exception {
        World w = world();
        Staff mine = staff(Role.AGENT, w.site(), w.group());
        Staff theirs = staff(Role.AGENT, w.site(), w.group());
        UUID myCounter = counter(w, "1");
        UUID theirCounter = counter(w, "2");
        UUID calledByMe = issueAgo(w.service(), 3);
        UUID mySession = openSession(mine, myCounter);
        assertThat(status(call(post("/api/v1/sessions/" + mySession + "/next"), mine.token(), null))).isEqualTo(200);
        UUID stillWaiting = issueAgo(w.service(), 2);
        UUID meantForMe = issueAgo(w.service(), 1);
        jdbc.update("UPDATE ticket SET target_agent_id = ? WHERE id = ?", mine.id(), meantForMe);
        openSession(theirs, theirCounter);

        MvcResult notMine = cancel(theirs, calledByMe, null);
        assertThat(status(notMine)).as("a ticket in another agent's hands").isEqualTo(403);
        assertThat(status(cancel(mine, stillWaiting, null))).as("a waiting ticket nobody meant for me").isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT state FROM ticket WHERE id = ?", String.class, calledByMe)).isEqualTo("called");
        assertThat(jdbc.queryForObject("SELECT state FROM ticket WHERE id = ?", String.class, stillWaiting)).isEqualTo("waiting");

        assertThat(status(cancel(mine, meantForMe, null))).as("a waiting ticket meant for me").isEqualTo(200);
        MvcResult own = cancel(mine, calledByMe, "Visitor is gone");
        assertThat(status(own)).as(body(own)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT state FROM ticket WHERE id = ?", String.class, calledByMe)).isEqualTo("cancelled");
    }

    @Test
    void aCancelIsCheckedOnTheServerForAuthenticationAndSite() throws Exception {
        World w = world();
        UUID ticket = issueAgo(w.service(), 3);
        assertThat(status(call(post("/api/v1/tickets/" + ticket + "/cancel"), null, null))).isEqualTo(401);

        World other = world();
        Staff elsewhere = staff(Role.RECEPTION_OPERATOR, other.site(), null);
        assertThat(status(cancel(elsewhere, ticket, null))).as("another site's ticket").isEqualTo(403);
        assertThat(status(cancel(elsewhere, UUID.randomUUID(), null))).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT state FROM ticket WHERE id = ?", String.class, ticket)).isEqualTo("waiting");
    }

    private UUID openSession(Staff agent, UUID counter) throws Exception {
        MvcResult result = call(post("/api/v1/sessions"), agent.token(), "{\"counter_id\":\"" + counter + "\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(201);
        return UUID.fromString(field(result, "$.id"));
    }

    private static Object inPayload(Map<String, Object> event, String path) {
        return JsonPath.read((String) event.get("payload"), path);
    }
}
