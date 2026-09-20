package com.qms.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.notification.NotificationMessageRepository.AttemptRow;
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
 * The job worker against real PostgreSQL (ticket 38, FR-NTF-003): a registered channel that succeeds, a channel
 * with no adapter registered falling straight to the next one with no wasted retry (FR-NTF-001, FR-NTF-005), and a
 * registered channel that keeps failing retrying with backoff before the message is terminally {@code failed} once
 * every channel in its order is exhausted (FR-NTF-033).
 */
@SpringBootTest
@Import({PostgresContainerConfig.class, NotificationSendWorkerIT.Clocks.class})
class NotificationSendWorkerIT {

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
        registry.add("qms.appointment.no-show-check-cron", () -> "-");
    }

    private static String newKeyDir() {
        try {
            return java.nio.file.Files.createTempDirectory("qms-keys-notification-worker").toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired NotificationSendWorker worker;
    @Autowired NotificationMessageRepository messages;
    @Autowired NotificationTemplateRepository templates;

    static final Instant BASE = Instant.parse("2026-09-21T04:00:00Z");

    @BeforeEach
    void startAtBase() {
        clock.set(BASE);
    }

    private UUID newSite() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\"]'::jsonb)",
                id, "S-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID newTicket(UUID site) {
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'GW')", group, site);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, 'W', 10, 30, '[\"reception\"]'::jsonb, true)",
                service, group);
        UUID visit = UUID.randomUUID();
        jdbc.update("INSERT INTO visit (id, site_id, started_at) VALUES (?, ?, now())", visit, site);
        UUID ticket = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ticket (id, token_number, sequence_no, reset_key, service_id, service_group_id, site_id, visit_id, origin_channel, state, issued_at, queued_at, secret_hash)"
                        + " VALUES (?, 'W-001', 1, 'daily', ?, ?, ?, ?, 'reception', 'waiting', now(), now(), 'hash')",
                ticket, service, group, site, visit);
        return ticket;
    }

    private void template(String triggerKey, String channel) {
        jdbc.update(
                "INSERT INTO notification_template (id, trigger_key, channel, language, subject, body) VALUES (?, ?, ?, 'en', NULL, 'Body')",
                UUID.randomUUID(), triggerKey, channel);
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notification_message WHERE id = ?", String.class, id);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT channel, channel_index, attempt_count, status FROM notification_message WHERE id = ?", id);
    }

    @Test
    void aMessageOnARegisteredWorkingChannelIsSent() {
        UUID site = newSite();
        UUID ticket = newTicket(site);
        UUID id = messages.insertQueued("your_turn", List.of("in_app"), "en", true, site, null, ticket, null, Map.of(), null, "Body", clock.instant());

        int processed = worker.tick();

        assertThat(processed).isEqualTo(1);
        assertThat(status(id)).isEqualTo("sent");
        List<AttemptRow> attempts = messages.attemptsOf(id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).status()).isEqualTo("sent");
        assertThat(attempts.get(0).channel()).isEqualTo("in_app");
    }

    @Test
    void aChannelWithNoRegisteredAdapterFallsStraightToTheNextOneWithNoWastedRetry() {
        UUID site = newSite();
        UUID ticket = newTicket(site);
        template("your_turn", "in_app");
        UUID id = messages.insertQueued("your_turn", List.of("web_push", "in_app"), "en", true, site, null, ticket, null, Map.of(), null, "Body", clock.instant());

        worker.tick(); // web_push has no adapter: fails at once and switches to in_app, not yet sent
        Map<String, Object> afterFirstTick = row(id);
        assertThat(afterFirstTick.get("status")).isEqualTo("queued");
        assertThat(afterFirstTick.get("channel")).isEqualTo("in_app");
        assertThat(afterFirstTick.get("channel_index")).isEqualTo(1);
        assertThat(afterFirstTick.get("attempt_count")).isEqualTo(0);

        worker.tick(); // in_app is due at once (next_attempt_at = now) and succeeds
        assertThat(status(id)).isEqualTo("sent");

        List<AttemptRow> attempts = messages.attemptsOf(id);
        assertThat(attempts).extracting(AttemptRow::channel).containsExactly("web_push", "in_app");
        assertThat(attempts).extracting(AttemptRow::status).containsExactly("failed", "sent");
        assertThat(attempts.get(0).providerResponse()).isEqualTo("no_adapter_registered");
    }

    @Test
    void aRegisteredChannelThatKeepsFailingRetriesWithBackoffThenIsTerminallyFailedOnceItsOrderIsExhausted() {
        // staff_alert is registered but refuses a message with no Site to publish to (Outcome.failure("no_site_topic")).
        UUID id = messages.insertQueued("queue_sla_breach", List.of("staff_alert"), "en", true, null, null, null, null, Map.of(), null, "Alert", clock.instant());

        worker.tick(); // attempt 1/3: fails, retry scheduled after the base backoff
        assertThat(status(id)).isEqualTo("queued");
        assertThat(row(id).get("attempt_count")).isEqualTo(1);

        clock.set(clock.instant().plusSeconds(31));
        worker.tick(); // attempt 2/3: fails, backoff doubles
        assertThat(status(id)).isEqualTo("queued");
        assertThat(row(id).get("attempt_count")).isEqualTo(2);

        clock.set(clock.instant().plusSeconds(61));
        worker.tick(); // attempt 3/3: fails, exhausted; staff_alert was the only channel in the order
        assertThat(status(id)).isEqualTo("failed");

        List<AttemptRow> attempts = messages.attemptsOf(id);
        assertThat(attempts).hasSize(3);
        assertThat(attempts).allMatch(a -> a.status().equals("failed") && a.channel().equals("staff_alert"));
        assertThat(attempts.get(0).providerResponse()).isEqualTo("no_site_topic");
    }

    @Test
    void aStaffAlertMessageWithARealSiteIsSent() {
        UUID site = newSite();
        UUID id = messages.insertQueued("queue_sla_breach", List.of("staff_alert"), "en", true, site, null, null, null, Map.of(), null, "Alert", clock.instant());

        worker.tick();

        assertThat(status(id)).isEqualTo("sent");
    }
}
