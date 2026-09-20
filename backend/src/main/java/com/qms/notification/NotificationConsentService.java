package com.qms.notification;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A visitor's consent for non-essential notifications (FR-NTF-035, FR-SEC-030): keyed on the visitor, not the
 * ticket, so it outlives any one visit; recorded with a timestamp and the version of the consent text the visitor
 * saw. {@code com.qms.issuance.VisitorTicketActions} calls {@link #setOptOut} from the visitor's own ticket page
 * (ticket 37's credential, never a bearer token); {@link NotificationDispatcher} calls {@link #isOptedOut} before
 * queuing a non-essential message (FR-NTF-035; essential triggers, {@link NotificationTriggerKey#essential()}, are
 * never opted out of).
 */
@Service
public class NotificationConsentService {

    private final JdbcTemplate jdbc;

    NotificationConsentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void setOptOut(UUID visitorId, boolean optedOut, String consentTextVersion, Instant now) {
        int updated = jdbc.update(
                "UPDATE notification_consent SET opted_out = ?, consent_text_version = ?, recorded_at = ? WHERE visitor_id = ?",
                optedOut, consentTextVersion, ts(now), visitorId);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO notification_consent (visitor_id, opted_out, consent_text_version, recorded_at) VALUES (?, ?, ?, ?)",
                    visitorId, optedOut, consentTextVersion, ts(now));
        }
    }

    public boolean isOptedOut(UUID visitorId) {
        if (visitorId == null) return false;
        Boolean optedOut = jdbc.query(
                "SELECT opted_out FROM notification_consent WHERE visitor_id = ?", rs -> rs.next() ? rs.getBoolean(1) : null, visitorId);
        return Boolean.TRUE.equals(optedOut);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
