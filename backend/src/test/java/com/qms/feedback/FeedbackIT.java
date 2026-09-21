package com.qms.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.qms.platform.security.Role;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
 * Post-service feedback end to end (ticket 45, FR-MOB-033): a visitor's own optional rating and comment on their
 * completed ticket, a Team Admin's own comment-approval queue, and an Agent's own read that never shows an
 * unapproved comment.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
class FeedbackIT {

    static final String PASSWORD = "Correct-Horse-9";
    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-feedback");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private record Setup(UUID site, UUID group, UUID service) {}

    private Setup setup() {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb)",
                site, "S-" + site.toString().substring(0, 8));
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'G')", group, site);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, booking_mode, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, 'A', 10, 30, '[\"reception\",\"kiosk\"]'::jsonb, 'both', true)",
                service, group);
        return new Setup(site, group, service);
    }

    private String userToken(Role role) throws Exception {
        UUID user = UUID.randomUUID();
        return userToken(role, user);
    }

    private String userToken(Role role, UUID user) throws Exception {
        String username = role.wire() + "-" + user;
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, display_name, preferred_language) VALUES (?, ?, ?, ?, ?)",
                user, username, new BCryptPasswordEncoder(12).encode(PASSWORD), role.wire(), "en");
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO role_assignments (id, user_id, role, site_ids, group_ids) VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, user);
            ps.setString(3, role.wire());
            ps.setArray(4, connection.createArrayOf("uuid", new UUID[0]));
            ps.setArray(5, connection.createArrayOf("uuid", new UUID[0]));
            return ps;
        });
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(status(login)).as(body(login)).isEqualTo(200);
        return field(login, "$.access_token");
    }

    private record IssuedTicket(UUID id, String secret) {}

    private IssuedTicket issue(String staffToken, UUID service) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + staffToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service_id\":\"" + service + "\",\"origin_channel\":\"reception\"}"))
                .andReturn();
        assertThat(status(result)).as(body(result)).isEqualTo(201);
        return new IssuedTicket(UUID.fromString(field(result, "$.id")), field(result, "$.secret"));
    }

    /** Fast-forwards a ticket straight to `completed`, bound to `agentId`, skipping the full call/serve/complete flow (not this ticket's own concern). */
    private void complete(UUID ticketId, UUID agentId) {
        jdbc.update("UPDATE ticket SET state = 'completed', agent_id = ? WHERE id = ?", agentId, ticketId);
    }

    private MvcResult submitFeedback(UUID ticketId, String secret, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/tickets/" + ticketId + "/feedback").contentType(MediaType.APPLICATION_JSON).content(body);
        if (secret != null) request.header("X-Ticket-Secret", secret);
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

    // ---- submit (FR-MOB-033) ------------------------------------------------------------------------------------

    @Test
    void aVisitorSubmitsFeedbackOnceTheirTicketIsCompleted() throws Exception {
        Setup s = setup();
        String staff = userToken(Role.RECEPTION_OPERATOR);
        UUID agent = UUID.randomUUID();
        userToken(Role.AGENT, agent);
        IssuedTicket ticket = issue(staff, s.service());
        complete(ticket.id(), agent);

        MvcResult result = submitFeedback(ticket.id(), ticket.secret(), "{\"rating\":5,\"comment\":\"Very helpful, thank you.\"}");
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat((Integer) field(result, "$.rating")).isEqualTo(5);
        assertThat((String) field(result, "$.comment")).isEqualTo("Very helpful, thank you.");

        assertThat(jdbc.queryForObject("SELECT rating FROM feedback WHERE ticket_id = ?", Integer.class, ticket.id())).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT comment_approved_at IS NULL FROM feedback WHERE ticket_id = ?", Boolean.class, ticket.id())).isTrue();
        Integer auditEntries = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'feedback.submitted' AND entity_id = ?", Integer.class, ticket.id());
        assertThat(auditEntries).isEqualTo(1);
    }

    @Test
    void feedbackIsOptionalAndTheRatingAloneIsEnoughWithNoComment() throws Exception {
        Setup s = setup();
        String staff = userToken(Role.RECEPTION_OPERATOR);
        UUID agent = UUID.randomUUID();
        userToken(Role.AGENT, agent);
        IssuedTicket ticket = issue(staff, s.service());
        complete(ticket.id(), agent);

        MvcResult result = submitFeedback(ticket.id(), ticket.secret(), "{\"rating\":3}");
        assertThat(status(result)).as(body(result)).isEqualTo(200);
        assertThat((Object) field(result, "$.comment")).isNull();
    }

    @Test
    void aVisitorCannotSubmitFeedbackBeforeTheirTicketIsCompleted() throws Exception {
        Setup s = setup();
        String staff = userToken(Role.RECEPTION_OPERATOR);
        IssuedTicket ticket = issue(staff, s.service());

        MvcResult refused = submitFeedback(ticket.id(), ticket.secret(), "{\"rating\":4}");
        assertThat(status(refused)).isEqualTo(409);
        assertThat((String) field(refused, "$.error.details.reason")).isEqualTo("ticket_not_completed");
    }

    @Test
    void aVisitorCannotSubmitFeedbackTwiceOnTheSameTicket() throws Exception {
        Setup s = setup();
        String staff = userToken(Role.RECEPTION_OPERATOR);
        UUID agent = UUID.randomUUID();
        userToken(Role.AGENT, agent);
        IssuedTicket ticket = issue(staff, s.service());
        complete(ticket.id(), agent);
        submitFeedback(ticket.id(), ticket.secret(), "{\"rating\":4}");

        MvcResult again = submitFeedback(ticket.id(), ticket.secret(), "{\"rating\":2}");
        assertThat(status(again)).isEqualTo(409);
        assertThat((String) field(again, "$.error.details.reason")).isEqualTo("feedback_already_submitted");
    }

    @Test
    void aWrongOrMissingSecretNeverSubmitsFeedbackEitherFR_SEC_033() throws Exception {
        Setup s = setup();
        String staff = userToken(Role.RECEPTION_OPERATOR);
        UUID agent = UUID.randomUUID();
        userToken(Role.AGENT, agent);
        IssuedTicket ticket = issue(staff, s.service());
        complete(ticket.id(), agent);

        MvcResult wrong = submitFeedback(ticket.id(), "not-the-right-secret", "{\"rating\":4}");
        assertThat(status(wrong)).isEqualTo(401);

        MvcResult missing = submitFeedback(ticket.id(), null, "{\"rating\":4}");
        assertThat(status(missing)).isEqualTo(401);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback WHERE ticket_id = ?", Integer.class, ticket.id())).isZero();
    }

    @Test
    void aRatingOutsideOneToFiveOrMissingIsValidationFailed() throws Exception {
        Setup s = setup();
        String staff = userToken(Role.RECEPTION_OPERATOR);
        UUID agent = UUID.randomUUID();
        userToken(Role.AGENT, agent);
        IssuedTicket ticket = issue(staff, s.service());
        complete(ticket.id(), agent);

        MvcResult tooHigh = submitFeedback(ticket.id(), ticket.secret(), "{\"rating\":6}");
        assertThat(status(tooHigh)).isEqualTo(400);
        assertThat((String) field(tooHigh, "$.error.details.fields[0].field")).isEqualTo("rating");

        MvcResult missing = submitFeedback(ticket.id(), ticket.secret(), "{\"comment\":\"no rating\"}");
        assertThat(status(missing)).isEqualTo(400);
        assertThat((String) field(missing, "$.error.details.fields[0].field")).isEqualTo("rating");
    }

    // ---- Team Admin approval and the Agent's own read (FR-MOB-033) ----------------------------------------------

    @Test
    void aTeamAdminApprovesAPendingCommentAndOnlyThenDoesTheAgentSeeIt() throws Exception {
        Setup s = setup();
        String staff = userToken(Role.RECEPTION_OPERATOR);
        UUID agentId = UUID.randomUUID();
        String agentToken = userToken(Role.AGENT, agentId);
        String teamAdminToken = userToken(Role.TEAM_ADMIN);
        IssuedTicket ticket = issue(staff, s.service());
        complete(ticket.id(), agentId);
        submitFeedback(ticket.id(), ticket.secret(), "{\"rating\":4,\"comment\":\"Friendly and quick.\"}");

        MvcResult mineBefore = mvc.perform(get("/api/v1/feedback/mine").header("Authorization", "Bearer " + agentToken)).andReturn();
        assertThat(status(mineBefore)).as(body(mineBefore)).isEqualTo(200);
        assertThat((Integer) field(mineBefore, "$.items[0].rating")).isEqualTo(4);
        assertThat((Object) field(mineBefore, "$.items[0].comment")).isNull();

        // The pending-comment queue is organisation-wide (every Team Admin sees every Site's queue, §5.2 has no scoping
        // row for this action), so another test's own still-unapproved comment may share it; find this ticket's own
        // row by its ticket id rather than assuming it is first.
        UUID feedbackId = jdbc.queryForObject("SELECT id FROM feedback WHERE ticket_id = ?", UUID.class, ticket.id());
        MvcResult pending = mvc.perform(get("/api/v1/feedback/pending-comments").header("Authorization", "Bearer " + teamAdminToken)).andReturn();
        assertThat(status(pending)).as(body(pending)).isEqualTo(200);
        assertThat(pendingComment(pending, feedbackId)).isEqualTo("Friendly and quick.");

        MvcResult approve = mvc.perform(post("/api/v1/feedback/" + feedbackId + "/approve-comment").header("Authorization", "Bearer " + teamAdminToken)).andReturn();
        assertThat(status(approve)).as(body(approve)).isEqualTo(200);
        assertThat((Boolean) field(approve, "$.comment_approved")).isTrue();

        MvcResult mineAfter = mvc.perform(get("/api/v1/feedback/mine").header("Authorization", "Bearer " + agentToken)).andReturn();
        assertThat((String) field(mineAfter, "$.items[0].comment")).isEqualTo("Friendly and quick.");

        Integer auditEntries = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'feedback.comment_approved' AND entity_id = ?", Integer.class, feedbackId);
        assertThat(auditEntries).isEqualTo(1);

        MvcResult pendingAfter = mvc.perform(get("/api/v1/feedback/pending-comments").header("Authorization", "Bearer " + teamAdminToken)).andReturn();
        assertThat(pendingComment(pendingAfter, feedbackId)).isNull();
    }

    /** This ticket's own row inside the (organisation-wide, possibly shared with other tests) pending-comment queue, or null once it is gone. */
    @SuppressWarnings("unchecked")
    private static String pendingComment(MvcResult pending, UUID feedbackId) throws Exception {
        List<Map<String, Object>> items = (List<Map<String, Object>>) field(pending, "$.items");
        return items.stream().filter(item -> feedbackId.toString().equals(item.get("id"))).map(item -> (String) item.get("comment")).findFirst().orElse(null);
    }

    @Test
    void approvingAFeedbackWithNoCommentIsRefused() throws Exception {
        Setup s = setup();
        String staff = userToken(Role.RECEPTION_OPERATOR);
        UUID agentId = UUID.randomUUID();
        userToken(Role.AGENT, agentId);
        String teamAdminToken = userToken(Role.TEAM_ADMIN);
        IssuedTicket ticket = issue(staff, s.service());
        complete(ticket.id(), agentId);
        submitFeedback(ticket.id(), ticket.secret(), "{\"rating\":5}");
        UUID feedbackId = jdbc.queryForObject("SELECT id FROM feedback WHERE ticket_id = ?", UUID.class, ticket.id());

        MvcResult refused = mvc.perform(post("/api/v1/feedback/" + feedbackId + "/approve-comment").header("Authorization", "Bearer " + teamAdminToken)).andReturn();
        assertThat(status(refused)).isEqualTo(409);
        assertThat((String) field(refused, "$.error.details.reason")).isEqualTo("no_comment");
    }

    @Test
    void onlyATeamAdminMayReadTheQueueOrApprove() throws Exception {
        Setup s = setup();
        String staff = userToken(Role.RECEPTION_OPERATOR);
        UUID agentId = UUID.randomUUID();
        String agentToken = userToken(Role.AGENT, agentId);
        IssuedTicket ticket = issue(staff, s.service());
        complete(ticket.id(), agentId);
        submitFeedback(ticket.id(), ticket.secret(), "{\"rating\":5,\"comment\":\"Great\"}");
        UUID feedbackId = jdbc.queryForObject("SELECT id FROM feedback WHERE ticket_id = ?", UUID.class, ticket.id());

        MvcResult pending = mvc.perform(get("/api/v1/feedback/pending-comments").header("Authorization", "Bearer " + agentToken)).andReturn();
        assertThat(status(pending)).isEqualTo(403);

        MvcResult approve = mvc.perform(post("/api/v1/feedback/" + feedbackId + "/approve-comment").header("Authorization", "Bearer " + agentToken)).andReturn();
        assertThat(status(approve)).isEqualTo(403);
    }

    @Test
    void onlyAnAgentMayReadTheirOwnFeedback() throws Exception {
        String teamAdminToken = userToken(Role.TEAM_ADMIN);

        MvcResult mine = mvc.perform(get("/api/v1/feedback/mine").header("Authorization", "Bearer " + teamAdminToken)).andReturn();
        assertThat(status(mine)).isEqualTo(403);
    }
}
