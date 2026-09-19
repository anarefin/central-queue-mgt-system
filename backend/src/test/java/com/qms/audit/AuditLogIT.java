package com.qms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.support.PostgresContainerConfig;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** FR-SEC-040..042: append-only, complete, searchable. */
@SpringBootTest
@Import(PostgresContainerConfig.class)
class AuditLogIT {

    @Autowired AuditWriter writer;
    @Autowired AuditRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private UUID record(String action) {
        UUID entityId = UUID.randomUUID();
        writer.record(AuditEvent.of(action, "user", entityId).withActor(UUID.randomUUID(), "org_admin"));
        return entityId;
    }

    @Test
    void theDatabaseRefusesUpdateDeleteAndTruncate() {
        UUID entityId = record("test.append_only");

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET action = 'tampered' WHERE entity_id = ?", entityId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log WHERE entity_id = ?", entityId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_log"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'test.append_only'",
                        Integer.class,
                        entityId))
                .isEqualTo(1);
    }

    @Test
    void noApplicationClassExposesAWayToEditOrDeleteEntries() {
        for (Class<?> type : List.of(AuditWriter.class, AuditRepository.class, AuditQueryService.class)) {
            for (var method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) && !Modifier.isProtected(method.getModifiers())) continue;
                assertThat(method.getName().toLowerCase())
                        .as(type.getSimpleName() + "." + method.getName())
                        .doesNotContainPattern("update|delete|remove|purge|truncate|edit|clear|erase");
            }
        }
    }

    @Test
    void recordsWhoWhatWhereAndWhy() {
        UUID entityId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        writer.record(AuditEvent.of("user.disabled", "user", entityId)
                .withActor(actor, "org_admin")
                .withBefore(Map.of("active", true))
                .withAfter(Map.of("active", false))
                .withReason("left the company"));

        AuditEntry entry = repository.search(new AuditFilter(null, null, null, entityId, null, null), null, 10).items().getFirst();
        assertThat(entry.actorId()).isEqualTo(actor);
        assertThat(entry.actorRole()).isEqualTo("org_admin");
        assertThat(entry.action()).isEqualTo("user.disabled");
        assertThat(entry.before()).containsEntry("active", true);
        assertThat(entry.after()).containsEntry("active", false);
        assertThat(entry.reason()).isEqualTo("left the company");
        assertThat(entry.occurredAt()).isNotNull();
    }

    @Test
    void takesActorSourceAddressAndDeviceFromTheCurrentRequestAndToken() {
        UUID sub = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject(sub.toString()).claim("roles", List.of("team_admin", "agent")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        request.addHeader("User-Agent", "TestBrowser/1.0");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        UUID entityId = UUID.randomUUID();

        writer.record(AuditEvent.of("config.changed", "site", entityId));

        AuditEntry entry = repository.search(new AuditFilter(null, null, null, entityId, null, null), null, 10).items().getFirst();
        assertThat(entry.actorId()).isEqualTo(sub);
        assertThat(entry.actorRole()).isEqualTo("agent,team_admin");
        assertThat(entry.ip()).isEqualTo("10.1.2.3");
        assertThat(entry.device()).isEqualTo("TestBrowser/1.0");
    }

    @Test
    void anEntryWithNoActorIsAllowedForFailedSignInsByUnknownUsers() {
        UUID entityId = UUID.randomUUID();
        writer.record(AuditEvent.of("auth.login.failed", "user", null).withAfter(Map.of("username", "nobody", "marker", entityId.toString())));

        var page = repository.search(new AuditFilter(null, "auth.login.failed", null, null, null, null), null, 200);
        assertThat(page.items()).anySatisfy(e -> {
            assertThat(e.actorId()).isNull();
            assertThat(e.after()).containsEntry("marker", entityId.toString());
        });
    }

    @Test
    void secretsInBeforeAndAfterAreNeverStored() {
        UUID entityId = UUID.randomUUID();
        writer.record(AuditEvent.of("user.created", "user", entityId).withAfter(Map.of("username", "a", "password", "hunter2")));

        AuditEntry entry = repository.search(new AuditFilter(null, null, null, entityId, null, null), null, 10).items().getFirst();
        assertThat(entry.after()).containsEntry("password", "[redacted]");
        assertThat(jdbc.queryForObject("SELECT after::text FROM audit_log WHERE entity_id = ?", String.class, entityId))
                .doesNotContain("hunter2");
    }

    @Test
    void searchFiltersByActorActionEntityAndTimeRange() {
        UUID entityId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        writer.record(AuditEvent.of("perm.granted", "user", entityId).withActor(actor, "org_admin"));
        writer.record(AuditEvent.of("perm.revoked", "user", entityId).withActor(actor, "org_admin"));
        writer.record(AuditEvent.of("perm.granted", "user", UUID.randomUUID()).withActor(UUID.randomUUID(), "agent"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        assertThat(repository.search(new AuditFilter(actor, null, null, null, null, null), null, 50).items()).hasSize(2);
        assertThat(repository.search(new AuditFilter(actor, "perm.granted", null, null, null, null), null, 50).items()).hasSize(1);
        assertThat(repository.search(new AuditFilter(actor, "perm.*", "user", entityId, null, null), null, 50).items()).hasSize(2);
        assertThat(repository.search(new AuditFilter(actor, null, null, null, now.plusMinutes(5), null), null, 50).items()).isEmpty();
        assertThat(repository.search(new AuditFilter(actor, null, null, null, null, now.minusMinutes(5)), null, 50).items()).isEmpty();
    }

    @Test
    void wildcardCharactersInAnActionFilterAreLiteral() {
        writer.record(AuditEvent.of("weird%action", "user", UUID.randomUUID()).withActor(UUID.randomUUID(), "x"));
        writer.record(AuditEvent.of("weirdXaction", "user", UUID.randomUUID()).withActor(UUID.randomUUID(), "x"));

        var page = repository.search(new AuditFilter(null, "weird%action", null, null, null, null), null, 50);

        assertThat(page.items()).extracting(AuditEntry::action).containsOnly("weird%action");
    }

    @Test
    void cursorPaginationVisitsEveryRowOnceEvenWithIdenticalTimestamps() {
        UUID actor = UUID.randomUUID();
        Instant same = Instant.parse("2031-01-01T00:00:00Z"); // far future, so these rows sort first and alone
        for (int i = 0; i < 25; i++) {
            jdbc.update(
                    "INSERT INTO audit_log (id, actor_id, actor_role, action, entity, occurred_at) VALUES (?, ?, 'x', 'page.test', 'user', ?)",
                    UUID.randomUUID(), actor, java.sql.Timestamp.from(same));
        }

        Set<UUID> seen = new HashSet<>();
        List<UUID> order = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var page = repository.search(new AuditFilter(actor, null, null, null, null, null), cursor == null ? null : AuditCursor.decode(cursor), 10);
            page.items().forEach(e -> {
                assertThat(seen.add(e.id())).as("no duplicates").isTrue();
                order.add(e.id());
            });
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(seen).hasSize(25);
        assertThat(pages).isEqualTo(3);
        // Ties are broken by id descending; PostgreSQL orders uuid by unsigned bytes, which equals text order.
        assertThat(order.stream().map(UUID::toString).toList()).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }
}
