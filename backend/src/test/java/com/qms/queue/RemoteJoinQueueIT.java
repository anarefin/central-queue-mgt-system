package com.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.qms.issuance.ActorType;
import com.qms.issuance.Channels;
import com.qms.issuance.IssuanceService;
import com.qms.issuance.IssueCommand;
import com.qms.issuance.TicketResponse;
import com.qms.support.PostgresContainerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A remote-joined ticket against real PostgreSQL (ticket 42, FR-MOB-012, FR-MOB-013, §19.1): it accrues its place and
 * an estimate in the queue exactly as a waiting ticket does, yet is still never the ticket a counter is given next —
 * proven here at the engine's own read path ({@link QueueReads}), the level {@code TicketTransition.CALL} and
 * {@code ticket_queue_idx} already enforce it at.
 */
@SpringBootTest
@Import(PostgresContainerConfig.class)
class RemoteJoinQueueIT {

    static final Path KEY_DIR = newKeyDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("qms.security.key-dir", KEY_DIR::toString);
    }

    private static Path newKeyDir() {
        try {
            return Files.createTempDirectory("qms-keys-remote-queue");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired IssuanceService issuanceService;
    @Autowired QueueReads queueReads;
    @Autowired JdbcTemplate jdbc;

    private record Setup(UUID site, UUID group, UUID service) {}

    private Setup setup(String prefix) {
        UUID site = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO site (id, name, code, timezone, address, default_language, enabled_languages) VALUES (?, 'Main', ?, 'Asia/Dhaka', '1 Road', 'en', '[\"en\"]'::jsonb)",
                site, "S-" + prefix);
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO service_group (id, site_id, name_i18n, token_prefix) VALUES (?, ?, '{\"en\":\"Desk\"}'::jsonb, ?)", group, site, "G" + prefix);
        UUID service = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service (id, service_group_id, name_i18n, token_prefix, expected_minutes, sla_wait_minutes, channels, active)"
                        + " VALUES (?, ?, '{\"en\":\"Consultation\"}'::jsonb, ?, 10, 30, '[\"reception\",\"mobile\"]'::jsonb, true)",
                service, group, "T" + prefix);
        jdbc.update(
                "INSERT INTO service_remote_rule (service_id, virtual_queue_enabled, max_remote_share_pct, join_window_minutes, arrival_deadline_minutes)"
                        + " VALUES (?, true, 100, 30, 15)",
                service);
        return new Setup(site, group, service);
    }

    private UUID visitor() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO visitor (id, external_code, name, category, created_at) VALUES (?, ?, 'V', 'general', now())", id, "V-" + id.toString().substring(0, 8));
        return id;
    }

    private TicketResponse joinRemote(UUID service) {
        UUID visitorId = visitor();
        var command = new IssueCommand(service, Channels.MOBILE, visitorId, ActorType.VISITOR, null, null, visitorId, false, null, null, null);
        return issuanceService.issueRemote(command, null, null);
    }

    private TicketResponse issueWaiting(UUID service) {
        return issuanceService.issue(new IssueCommand(service, Channels.RECEPTION, UUID.randomUUID(), ActorType.SYSTEM, null));
    }

    @Test
    void aRemoteTicketIsRankedWithAPositionAndAnEstimateJustLikeAWaitingOne() {
        Setup s = setup("PQ");

        TicketResponse remote = joinRemote(s.service());

        assertThat(remote.state()).isEqualTo("remote");
        assertThat(remote.position()).as("a remote ticket accrues a place in the queue (FR-MOB-012, FR-MOB-013)").isEqualTo(1);
        assertThat(remote.estimatedWait()).isNotNull();
        assertThat(queueReads.waitingCount(s.service())).as("counted in the queue's own size").isEqualTo(1);
    }

    @Test
    void aLaterWaitingTicketRanksBehindAnEarlierRemoteOneButIsTheOneDrawnNext() {
        Setup s = setup("PR");

        TicketResponse remote = joinRemote(s.service()); // queued first
        TicketResponse waiting = issueWaiting(s.service()); // queued second

        QueueReads.Ordered ordered = queueReads.ordered(s.service(), null);
        assertThat(ordered.entries()).extracting(QueueReads.Entry::ticketId).containsExactly(remote.id(), waiting.id());

        // Yet a counter session drawing next is given the waiting ticket, never the remote one (§19.1): the remote
        // ticket must be checked in first (ticket 43), no matter how early it joined.
        Optional<QueueReads.Entry> head = queueReads.callableHead(s.service(), null, null);
        assertThat(head).isPresent();
        assertThat(head.get().ticketId()).isEqualTo(waiting.id());
        assertThat(head.get().state()).isEqualTo("waiting");
    }

    @Test
    void aQueueOfOnlyARemoteTicketHasNoCallableHeadAtAll() {
        Setup s = setup("PN");
        joinRemote(s.service());

        assertThat(queueReads.callableHead(s.service(), null, null)).isEmpty();
    }
}
