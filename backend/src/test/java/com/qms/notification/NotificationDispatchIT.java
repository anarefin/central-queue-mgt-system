package com.qms.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.platform.notifications.NotificationContext;
import com.qms.platform.notifications.NotificationTrigger;
import com.qms.queue.TicketEvents;
import com.qms.support.MutableClock;
import com.qms.support.PostgresContainerConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The dispatcher against real PostgreSQL (ticket 38): trigger enablement per Site/Service (FR-NTF-010), the visitor
 * language fallback (FR-NTF-022), the per-Service "include service name" flag (FR-NTF-034), quiet hours (FR-NTF-031),
 * throttling (FR-NTF-030), opt-out persisting across visits (FR-NTF-035) and the real wiring from a queue transition
 * (FR-NTF-001, FR-NTF-003) through {@link TicketEvents}.
 */
@SpringBootTest
@Import({PostgresContainerConfig.class, NotificationDispatchIT.Clocks.class})
class NotificationDispatchIT {

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
        registry.add("qms.security.key-dir", () -> newKeyDir());
        registry.add("qms.notification.send-poll-cron", () -> "-");
        registry.add("qms.appointment.hold-expiry-check-cron", () -> "-");
        registry.add("qms.appointment.reminder-check-cron", () -> "-");
        registry.add("qms.appointment.no-show-check-cron", () -> "-");
    }

    private static String newKeyDir() {
        try {
            return java.nio.file.Files.createTempDirectory("qms-keys-notification-dispatch").toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired TicketEvents ticketEvents;
    @Autowired NotificationTrigger notifications;
    @Autowired NotificationMessageRepository messages;
    @Autowired NotificationConsentService consent;
    @Autowired NotificationTriggerConfigRepository triggerConfigRepository;

    static final Instant BASE = Instant.parse("2026-09-21T04:00:00Z"); // Monday 10:00 in Asia/Dhaka (UTC+6)

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
    }

    // ---- fixtures --------------------------------------------------------------------------------------------

    private record Setup(UUID site, UUID group, UUID service, UUID zone, UUID counter) {}

    private UUID newSite(String prefix, String quietStart, String quietEnd) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages, quiet_hours_start, quiet_hours_end)"
                        + " VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\",\"bn\"]'::jsonb, ?::time, ?::time)",
                id, "S-" + prefix + "-" + id.toString().substring(0, 6), quietStart, quietEnd);
        return id;
    }

    private Setup setup(String prefix) {
        UUID site = newSite(prefix, null, null);
        return setupOn(site, prefix);
    }

    private Setup setupOn(UUID site, String prefix) {
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, ?)", group, site, "G" + prefix);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, ?, 10, 30, '[\"reception\"]'::jsonb, true)",
                service, group, prefix);
        UUID zone = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, floor_label) VALUES (?, ?, 'Zone A', 'Ground')", zone, site);
        UUID counter = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, 'Counter 1')", counter, zone);
        return new Setup(site, group, service, zone, counter);
    }

    private UUID newVisitor(String language) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO visitor (id, external_code, name, category, created_at, preferred_language) VALUES (?, ?, 'Karim', 'general', now(), ?)",
                id, "V-" + id.toString().substring(0, 8), language);
        return id;
    }

    private UUID newVisit(UUID site) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO visit (id, site_id, started_at) VALUES (?, ?, now())", id, site);
        return id;
    }

    private UUID newTicket(Setup s, UUID visitorId, String state, String originChannel) {
        UUID id = UUID.randomUUID();
        UUID visit = newVisit(s.site());
        jdbc.update(
                "INSERT INTO ticket (id, token_number, sequence_no, reset_key, service_id, service_group_id, site_id, zone_id, visit_id, origin_channel,"
                        + " state, issued_at, queued_at, secret_hash, visitor_id)"
                        + " VALUES (?, ?, 1, 'daily', ?, ?, ?, ?, ?, ?, ?, now(), now(), 'hash', ?)",
                id, "A-" + id.toString().substring(0, 4).toUpperCase(java.util.Locale.ROOT), s.service(), s.group(), s.site(), s.zone(), visit, originChannel, state, visitorId);
        return id;
    }

    // Templates are global (trigger x channel x language), not scoped to a Site, so upsert: several test methods in
    // this shared-database class author the same "your_turn"/"web_push"/"en" template.
    private void template(String triggerKey, String channel, String language, String body) {
        jdbc.update(
                "INSERT INTO notification_template (id, trigger_key, channel, language, subject, body) VALUES (?, ?, ?, ?, NULL, ?)"
                        + " ON CONFLICT (trigger_key, channel, language) DO UPDATE SET body = EXCLUDED.body",
                UUID.randomUUID(), triggerKey, channel, language, body);
    }

    private Map<String, Object> messageRow(UUID ticketId) {
        return jdbc.queryForList("SELECT * FROM notification_message WHERE ticket_id = ? ORDER BY created_at", ticketId).stream()
                .reduce((a, b) -> b) // latest
                .orElseThrow();
    }

    private Map<String, Object> messageRowByStatus(UUID ticketId, String status) {
        return jdbc.queryForList("SELECT * FROM notification_message WHERE ticket_id = ? AND status = ?", ticketId, status).stream()
                .findFirst()
                .orElseThrow();
    }

    private int messageCount(UUID ticketId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM notification_message WHERE ticket_id = ?", Integer.class, ticketId);
        return count == null ? 0 : count;
    }

    // ---- FR-NTF-001, FR-NTF-003: a real queue transition queues a message through TicketEvents ------------------

    @Test
    void aRealTicketCallQueuesAnInAppMessageThroughTheProductionWiring() {
        Setup s = setup("CALL");
        template("your_turn", "web_push", "en", "Your token {{token_number}} is up at {{counter_label}}");
        template("your_turn", "in_app", "en", "Your token {{token_number}}");
        UUID visitor = newVisitor(null);
        UUID ticket = newTicket(s, visitor, "waiting", "reception");

        ticketEvents.append(new TicketEvents.Transition(
                ticket, "ticket.called", "waiting", "called", UUID.randomUUID(), "staff", s.counter(), null, clock.instant(), clock.instant()));

        Map<String, Object> row = messageRow(ticket);
        assertThat(row.get("trigger_key")).isEqualTo("your_turn");
        assertThat(row.get("status")).isEqualTo("queued");
        // your_turn's default order is [web_push, in_app]; the message starts on the first channel.
        assertThat(row.get("channel")).isEqualTo("web_push");
    }

    @Test
    void aReannounceThatLeavesTheStateUnchangedFiresNoTrigger() {
        Setup s = setup("REANN");
        template("your_turn", "web_push", "en", "Your token {{token_number}}");
        UUID ticket = newTicket(s, null, "called", "reception");

        ticketEvents.append(new TicketEvents.Transition(
                ticket, "ticket.called", "called", "called", UUID.randomUUID(), "staff", s.counter(), null, clock.instant(), clock.instant()));

        assertThat(messageCount(ticket)).isZero();
    }

    // ---- FR-NTF-010: enabled per Site and per Service, service overriding site overriding the catalogue default --

    @Test
    void aTriggerDisabledAtSiteLevelIsNotQueued() {
        Setup s = setup("SITEOFF");
        template("your_turn", "web_push", "en", "Your token {{token_number}}");
        triggerConfigRepository.upsertSite(s.site(), "your_turn", false, null, null, clock.instant());
        UUID ticket = newTicket(s, null, "waiting", "reception");

        fireYourTurn(s, ticket, null);

        assertThat(messageCount(ticket)).isZero();
    }

    @Test
    void aServiceLevelOverrideWinsOverTheSitesOwnSetting() {
        Setup s = setup("SVCOVERRIDE");
        template("your_turn", "web_push", "en", "Your token {{token_number}}");
        triggerConfigRepository.upsertSite(s.site(), "your_turn", false, null, null, clock.instant());
        triggerConfigRepository.upsertService(s.site(), s.service(), "your_turn", true, null, null, clock.instant());
        UUID ticket = newTicket(s, null, "waiting", "reception");

        fireYourTurn(s, ticket, null);

        assertThat(messageCount(ticket)).isEqualTo(1);
    }

    // ---- FR-NTF-022: visitor preference falling back to the Site default -----------------------------------------

    @Test
    void messagesRenderInTheVisitorsPreferredLanguage() {
        Setup s = setup("LANG");
        template("your_turn", "web_push", "bn", "আপনার টোকেন {{token_number}}");
        template("your_turn", "web_push", "en", "Your token {{token_number}}");
        UUID visitor = newVisitor("bn");
        UUID ticket = newTicket(s, visitor, "waiting", "reception");

        fireYourTurn(s, ticket, visitor);

        assertThat(messageRow(ticket).get("language")).isEqualTo("bn");
    }

    @Test
    void aVisitorWithNoPreferenceFallsBackToTheSitesDefaultLanguage() {
        Setup s = setup("LANGDEF");
        template("your_turn", "web_push", "en", "Your token {{token_number}}");
        UUID visitor = newVisitor(null);
        UUID ticket = newTicket(s, visitor, "waiting", "reception");

        fireYourTurn(s, ticket, null);

        assertThat(messageRow(ticket).get("language")).isEqualTo("en");
    }

    // ---- FR-NTF-034: service name inclusion is a per-Service flag ------------------------------------------------

    @Test
    void suppressingTheServiceNameLeavesTheVariableBlank() {
        Setup s = setup("SVCNAME");
        jdbc.update("UPDATE service SET include_service_name_in_notifications = false WHERE id = ?", s.service());
        template("your_turn", "web_push", "en", "[{{service_name}}] token {{token_number}}");
        UUID ticket = newTicket(s, null, "waiting", "reception");

        fireYourTurn(s, ticket, null);

        assertThat((String) messageRow(ticket).get("rendered_body")).isEqualTo("[] token " + tokenOf(ticket));
    }

    // ---- FR-SEC-021 (ticket 54): a clinical-sensitivity Site never names the real service in a notification -------

    @Test
    void aClinicalSensitivitySiteReplacesTheServiceNameWithANeutralLabel() {
        Setup s = setup("CLINICAL");
        jdbc.update("UPDATE site SET clinical_sensitivity = true WHERE id = ?", s.site());
        template("your_turn", "web_push", "en", "[{{service_name}}/{{service_group_name}}] token {{token_number}}");
        UUID ticket = newTicket(s, null, "waiting", "reception");

        fireYourTurn(s, ticket, null);

        assertThat((String) messageRow(ticket).get("rendered_body")).isEqualTo("[Service/Service] token " + tokenOf(ticket));
    }

    @Test
    void aNonClinicalSiteStillNamesTheRealService() {
        Setup s = setup("NONCLINICAL");
        template("your_turn", "web_push", "en", "[{{service_name}}] token {{token_number}}");
        UUID ticket = newTicket(s, null, "waiting", "reception");

        fireYourTurn(s, ticket, null);

        assertThat((String) messageRow(ticket).get("rendered_body")).isEqualTo("[Consultation] token " + tokenOf(ticket));
    }

    // ---- FR-NTF-031: quiet hours suppress non-urgent messages, essential ones bypass -------------------------------

    @Test
    void aNonEssentialTriggerIsSuppressedDuringQuietHoursWithNoLaterBatch() {
        Setup s = setupOn(newSite("QUIET", "22:00:00", "06:00:00"), "QUIET");
        template("marked_no_show", "web_push", "en", "No-show for {{token_number}}");
        UUID ticket = newTicket(s, null, "called", "reception");
        clock.set(Instant.parse("2026-09-21T17:00:00Z")); // 23:00 Dhaka: inside the 22:00-06:00 window

        ticketEvents.append(new TicketEvents.Transition(
                ticket, "ticket.no_show", "called", "no_show", UUID.randomUUID(), "staff", s.counter(), null, clock.instant(), clock.instant()));

        Map<String, Object> row = messageRow(ticket);
        assertThat(row.get("status")).isEqualTo("suppressed");
        assertThat(row.get("suppressed_reason")).isEqualTo("quiet_hours");
    }

    @Test
    void anEssentialTriggerBypassesQuietHours() {
        Setup s = setupOn(newSite("QUIETURGENT", "22:00:00", "06:00:00"), "QUIETURGENT");
        template("your_turn", "web_push", "en", "Your token {{token_number}}");
        UUID ticket = newTicket(s, null, "waiting", "reception");
        clock.set(Instant.parse("2026-09-21T17:00:00Z")); // inside quiet hours

        fireYourTurn(s, ticket, null);

        assertThat(messageRow(ticket).get("status")).isEqualTo("queued");
    }

    // ---- FR-NTF-030: throttling per ticket and per day --------------------------------------------------------

    @Test
    void aTicketPastItsMessageLimitIsThrottled() {
        Setup s = setup("THROTTLETICKET");
        template("your_turn", "web_push", "en", "Your token {{token_number}}");
        template("marked_no_show", "web_push", "en", "No-show {{token_number}}");
        UUID ticket = newTicket(s, null, "waiting", "reception");
        for (int i = 0; i < 4; i++) {
            notifications.fire("your_turn", new NotificationContext(s.site(), s.service(), ticket, null, s.counter(), tokenOf(ticket), clock.instant(), null, null));
        }
        assertThat(messageCount(ticket)).isEqualTo(4);

        notifications.fire("marked_no_show", new NotificationContext(s.site(), s.service(), ticket, null, null, tokenOf(ticket), clock.instant(), null, null));

        assertThat(messageCount(ticket)).isEqualTo(5);
        Map<String, Object> suppressed = messageRowByStatus(ticket, "suppressed");
        assertThat(suppressed.get("suppressed_reason")).isEqualTo("throttled_ticket");
    }

    @Test
    void aVisitorPastTheirDailyMessageLimitAcrossTicketsIsThrottled() {
        Setup s = setup("THROTTLEDAY");
        template("your_turn", "web_push", "en", "Your token {{token_number}}");
        UUID visitor = newVisitor(null);
        for (int i = 0; i < 10; i++) {
            UUID ticket = newTicket(s, visitor, "waiting", "reception");
            notifications.fire("your_turn", new NotificationContext(s.site(), s.service(), ticket, visitor, s.counter(), tokenOf(ticket), clock.instant(), null, null));
        }
        UUID eleventh = newTicket(s, visitor, "waiting", "reception");

        notifications.fire("your_turn", new NotificationContext(s.site(), s.service(), eleventh, visitor, s.counter(), tokenOf(eleventh), clock.instant(), null, null));

        Map<String, Object> row = messageRow(eleventh);
        assertThat(row.get("status")).isEqualTo("suppressed");
        assertThat(row.get("suppressed_reason")).isEqualTo("throttled_daily");
    }

    // ---- FR-NTF-035, FR-SEC-030: opt-out persists across visits ---------------------------------------------------

    @Test
    void optOutSuppressesANonEssentialTriggerAndPersistsForANewTicketOfTheSameVisitor() {
        Setup s = setup("OPTOUT");
        template("marked_no_show", "web_push", "en", "No-show {{token_number}}");
        UUID visitor = newVisitor(null);
        consent.setOptOut(visitor, true, "v1", clock.instant());

        UUID firstTicket = newTicket(s, visitor, "called", "reception");
        notifications.fire("marked_no_show", new NotificationContext(s.site(), s.service(), firstTicket, visitor, null, tokenOf(firstTicket), clock.instant(), null, null));
        assertThat(messageCount(firstTicket)).isZero();

        // A new ticket, a later visit: the opt-out is keyed on the visitor, so it still applies (FR-NTF-035).
        UUID secondTicket = newTicket(s, visitor, "called", "reception");
        notifications.fire("marked_no_show", new NotificationContext(s.site(), s.service(), secondTicket, visitor, null, tokenOf(secondTicket), clock.instant(), null, null));
        assertThat(messageCount(secondTicket)).isZero();
    }

    @Test
    void optOutNeverSuppressesAnEssentialTrigger() {
        Setup s = setup("OPTOUTESSENTIAL");
        template("your_turn", "web_push", "en", "Your token {{token_number}}");
        UUID visitor = newVisitor(null);
        consent.setOptOut(visitor, true, "v1", clock.instant());
        UUID ticket = newTicket(s, visitor, "waiting", "reception");

        notifications.fire("your_turn", new NotificationContext(s.site(), s.service(), ticket, visitor, s.counter(), tokenOf(ticket), clock.instant(), null, null));

        assertThat(messageCount(ticket)).isEqualTo(1);
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private void fireYourTurn(Setup s, UUID ticket, UUID visitorId) {
        notifications.fire("your_turn", new NotificationContext(s.site(), s.service(), ticket, visitorId, s.counter(), tokenOf(ticket), clock.instant(), null, null));
    }

    private String tokenOf(UUID ticketId) {
        return jdbc.queryForObject("SELECT token_number FROM ticket WHERE id = ?", String.class, ticketId);
    }
}
