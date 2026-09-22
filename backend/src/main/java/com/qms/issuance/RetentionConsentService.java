package com.qms.issuance;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A visitor's own consent for retention (FR-SEC-030, ticket 54): keyed on the visitor like {@code
 * com.qms.notification.NotificationConsentService}'s own consent for notifications, recorded with a timestamp and
 * the version of the consent text they saw. {@code VisitorTicketActions} calls {@link #setConsent} from the
 * visitor's own ticket page, the same shape {@code setNotificationOptOut} already uses.
 */
@Service
class RetentionConsentService {

    private final JdbcTemplate jdbc;

    RetentionConsentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    void setConsent(UUID visitorId, boolean granted, String consentTextVersion, Instant now) {
        int updated = jdbc.update(
                "UPDATE visitor_retention_consent SET granted = ?, consent_text_version = ?, recorded_at = ? WHERE visitor_id = ?",
                granted, consentTextVersion, ts(now), visitorId);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO visitor_retention_consent (visitor_id, granted, consent_text_version, recorded_at) VALUES (?, ?, ?, ?)",
                    visitorId, granted, consentTextVersion, ts(now));
        }
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
