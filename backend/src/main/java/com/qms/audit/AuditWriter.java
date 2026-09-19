package com.qms.audit;

import com.qms.platform.TraceIds;
import com.qms.platform.security.AuthenticatedUser;
import com.qms.platform.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only way into the audit log, and it can only add (FR-SEC-042). Actor, role, source address, device and trace id
 * are filled from the current token and request (FR-SEC-041). The write joins the caller's transaction, so a
 * permission change and its audit entry commit or roll back together.
 */
@Component
public class AuditWriter {

    private static final int MAX_DEVICE_LENGTH = 200;
    private static final String INSERT =
            "INSERT INTO audit_log (id, actor_id, actor_role, action, entity, entity_id, before, after, ip, device, reason, trace_id, occurred_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final Clock clock;
    private final CurrentUser currentUser;

    AuditWriter(JdbcTemplate jdbc, JsonMapper mapper, Clock clock, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.currentUser = currentUser;
    }

    public void record(AuditEvent event) {
        AuthenticatedUser user = currentUser.get().orElse(null);
        UUID actorId = event.actorId() != null ? event.actorId() : user == null ? null : user.userId();
        String actorRole = event.actorRole() != null ? event.actorRole() : user == null ? null : user.rolesCsv();
        HttpServletRequest request = currentRequest();

        jdbc.update(
                INSERT,
                UUID.randomUUID(),
                actorId,
                actorRole,
                event.action(),
                event.entity(),
                event.entityId(),
                json(AuditRedaction.scrub(event.before())),
                json(AuditRedaction.scrub(event.after())),
                request == null ? null : request.getRemoteAddr(),
                request == null ? null : truncate(request.getHeader("User-Agent")),
                event.reason(),
                MDC.get(TraceIds.MDC_KEY),
                clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC));
    }

    private String json(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_DEVICE_LENGTH ? value : value.substring(0, MAX_DEVICE_LENGTH);
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
}
