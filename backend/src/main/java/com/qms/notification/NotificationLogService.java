package com.qms.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * The admin delivery log (FR-NTF-032): every message, filterable by ticket, visitor and status, with its attempts.
 * Reuses {@code audit:read} — an org- or system-wide, read-only view of what the system did, the same shape audit
 * entries already are — rather than a new permission for one more read-only screen.
 */
@Service
public class NotificationLogService {

    private static final int DEFAULT_LIMIT = 100;

    private final NotificationMessageRepository messages;

    NotificationLogService(NotificationMessageRepository messages) {
        this.messages = messages;
    }

    @PreAuthorize("hasAuthority(T(com.qms.platform.security.Authorities).AUDIT_READ)")
    public List<MessageWithAttempts> search(UUID ticketId, UUID visitorId, String status, Integer limit) {
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 500);
        return messages.forAdmin(ticketId, visitorId, status, effectiveLimit).stream()
                .map(row -> new MessageWithAttempts(row, messages.attemptsOf(row.id())))
                .toList();
    }
}
