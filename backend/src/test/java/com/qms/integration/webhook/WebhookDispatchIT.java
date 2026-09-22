package com.qms.integration.webhook;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The real wiring from a queue transition to a queued webhook delivery (ticket 57, FR-INT-020): a real
 * {@code ticket.called} through {@link TicketEvents} fans out to four realtime topics (queue:/ticket:/counter:/zone:)
 * with the same event type, occurred_at and data every time, and this asserts exactly one {@code webhook_event} and
 * one delivery per subscribed endpoint result — never four. Also covers FR-INT-022: the transition itself commits
 * and returns normally regardless of what endpoints are configured, since a delivery is only ever queued for a later
 * sweep, not sent inline.
 */
@SpringBootTest
@Import({PostgresContainerConfig.class, WebhookDispatchIT.Clocks.class})
class WebhookDispatchIT {

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
        registry.add("qms.webhook.send-poll-cron", () -> "-");
        registry.add("qms.notification.send-poll-cron", () -> "-");
        registry.add("qms.appointment.hold-expiry-check-cron", () -> "-");
        registry.add("qms.appointment.reminder-check-cron", () -> "-");
        registry.add("qms.appointment.no-show-check-cron", () -> "-");
    }

    private static String newKeyDir() {
        try {
            return java.nio.file.Files.createTempDirectory("qms-keys-webhook-dispatch").toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired TicketEvents ticketEvents;
    @Autowired WebhookEndpointRepository endpoints;

    static final Instant BASE = Instant.parse("2026-09-21T04:00:00Z");

    @BeforeEach
    void startAtBaseWithNoEndpointsLeftFromAnEarlierTest() {
        clock.set(BASE);
        // Endpoints are organisation-wide (no Site of their own to scope a fresh fixture by, unlike most of this
        // suite's other tables), and this class's tests share one Postgres container with no rollback between them:
        // without this, an endpoint another test method created and left active would still be "subscribed" here.
        jdbc.update("DELETE FROM webhook_delivery_attempt");
        jdbc.update("DELETE FROM webhook_delivery");
        jdbc.update("DELETE FROM webhook_event");
        jdbc.update("DELETE FROM webhook_endpoint");
    }

    private record Setup(UUID site, UUID group, UUID service, UUID zone, UUID counter) {}

    private Setup setup() {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main campus', ?, 'Asia/Dhaka', '1 Campus Road', 'en', '[\"en\"]'::jsonb)",
                site, "S-" + site.toString().substring(0, 8));
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Outpatient\"}'::jsonb, 'GW')", group, site);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, 'W', 10, 30, '[\"reception\"]'::jsonb, true)",
                service, group);
        UUID zone = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, site_id, name, floor_label) VALUES (?, ?, 'Zone A', 'Ground')", zone, site);
        UUID counter = UUID.randomUUID();
        jdbc.update("INSERT INTO counter (id, zone_id, label) VALUES (?, ?, 'Counter 1')", counter, zone);
        return new Setup(site, group, service, zone, counter);
    }

    private UUID newTicket(Setup s, String state) {
        UUID visit = UUID.randomUUID();
        jdbc.update("INSERT INTO visit (id, site_id, started_at) VALUES (?, ?, now())", visit, s.site());
        UUID ticket = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ticket (id, token_number, sequence_no, reset_key, service_id, service_group_id, site_id, zone_id, visit_id, origin_channel, state, issued_at, queued_at, secret_hash)"
                        + " VALUES (?, 'W-001', 1, 'daily', ?, ?, ?, ?, ?, 'reception', ?, now(), now(), 'hash')",
                ticket, s.service(), s.group(), s.site(), s.zone(), visit, state);
        return ticket;
    }

    private UUID subscribeEndpoint(String... eventTypes) {
        return endpoints.insert("Test endpoint", "https://203.0.113.10/hooks", List.of(eventTypes), "secret", UUID.randomUUID(), clock.instant());
    }

    /** Scoped to this one ticket's own events: several tests share the one Postgres container with no rollback
     * between them, so an unscoped {@code count(*)} would also see every other test's own rows. */
    private int eventCountFor(UUID ticketId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM webhook_event WHERE data->>'ticket_id' = ?", Integer.class, ticketId.toString());
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> deliveriesFor(UUID endpointId) {
        return jdbc.queryForList("SELECT * FROM webhook_delivery WHERE endpoint_id = ?", endpointId);
    }

    // ---- FR-INT-020: exactly one delivery per subscribed endpoint, despite the topic fan-out -----------------------

    @Test
    void aRealTicketCalledTransitionQueuesExactlyOneDeliveryPerSubscribedEndpointDespiteFourTopicPublishes() {
        Setup s = setup();
        UUID ticket = newTicket(s, "waiting");
        UUID endpoint = subscribeEndpoint("ticket.called");

        ticketEvents.append(new TicketEvents.Transition(
                ticket, "ticket.called", "waiting", "called", UUID.randomUUID(), "staff", s.counter(), null, clock.instant(), clock.instant()));

        // TicketEvents.publish fans this one transition out to queue:/ticket:/counter:/zone: (four RealtimeEventOccurred
        // raises, same type/occurred_at/data each time) — the dedup key must collapse them to one event, one delivery.
        assertThat(eventCountFor(ticket)).isEqualTo(1);
        List<Map<String, Object>> deliveries = deliveriesFor(endpoint);
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).get("status")).isEqualTo("queued");
        assertThat(deliveries.get(0).get("event_type")).isEqualTo("ticket.called");
    }

    @Test
    void anEndpointNotSubscribedToTheEventTypeGetsNoDelivery() {
        Setup s = setup();
        UUID ticket = newTicket(s, "waiting");
        UUID endpoint = subscribeEndpoint("ticket.completed"); // not ticket.called

        ticketEvents.append(new TicketEvents.Transition(
                ticket, "ticket.called", "waiting", "called", UUID.randomUUID(), "staff", s.counter(), null, clock.instant(), clock.instant()));

        assertThat(deliveriesFor(endpoint)).isEmpty();
    }

    @Test
    void noEndpointConfiguredRecordsNoEventAtAll() {
        Setup s = setup();
        UUID ticket = newTicket(s, "waiting");

        ticketEvents.append(new TicketEvents.Transition(
                ticket, "ticket.called", "waiting", "called", UUID.randomUUID(), "staff", s.counter(), null, clock.instant(), clock.instant()));

        assertThat(eventCountFor(ticket)).isZero();
    }

    @Test
    void twoEndpointsSubscribedToTheSameTypeEachGetTheirOwnDeliveryOfTheSameEvent() {
        Setup s = setup();
        UUID ticket = newTicket(s, "waiting");
        UUID first = subscribeEndpoint("ticket.called");
        UUID second = subscribeEndpoint("ticket.called", "ticket.completed");

        ticketEvents.append(new TicketEvents.Transition(
                ticket, "ticket.called", "waiting", "called", UUID.randomUUID(), "staff", s.counter(), null, clock.instant(), clock.instant()));

        assertThat(eventCountFor(ticket)).isEqualTo(1);
        assertThat(deliveriesFor(first)).hasSize(1);
        assertThat(deliveriesFor(second)).hasSize(1);
    }

    // ---- FR-INT-022: the queue transition itself is entirely unaffected by webhook configuration -------------------

    @Test
    void theTransitionCommitsAndTheTicketStateAdvancesRegardlessOfAnyWebhookConfiguration() {
        Setup s = setup();
        UUID ticket = newTicket(s, "waiting");
        subscribeEndpoint("ticket.called");

        ticketEvents.append(new TicketEvents.Transition(
                ticket, "ticket.called", "waiting", "called", UUID.randomUUID(), "staff", s.counter(), null, clock.instant(), clock.instant()));

        // The transition's own effect (the ticket_event row) is unaffected by webhook delivery being purely
        // asynchronous: nothing here waits on, or can be broken by, an unreachable endpoint.
        Integer events = jdbc.queryForObject("SELECT count(*) FROM ticket_event WHERE ticket_id = ? AND event_type = 'ticket.called'", Integer.class, ticket);
        assertThat(events).isEqualTo(1);
    }
}
