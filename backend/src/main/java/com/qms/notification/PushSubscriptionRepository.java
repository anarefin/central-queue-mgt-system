package com.qms.notification;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The {@code push_subscription} table (ticket 39, §18.3): one row per browser subscription, keyed on its own endpoint. */
@Repository
class PushSubscriptionRepository {

    record Row(UUID id, UUID ticketId, String endpoint, String p256dh, String auth) {

        byte[] p256dhBytes() {
            return decodeBase64Url(p256dh);
        }

        byte[] authBytes() {
            return decodeBase64Url(auth);
        }
    }

    private final JdbcTemplate jdbc;

    PushSubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A device that already holds a subscription for this endpoint (a re-subscribe, or a new ticket on the same
     * device) upserts in place rather than accumulating a duplicate row, and un-revokes it if it had been revoked. */
    void upsert(UUID ticketId, UUID visitorId, String endpoint, String p256dh, String auth, Instant now) {
        int updated = jdbc.update(
                "UPDATE push_subscription SET ticket_id = ?, visitor_id = ?, p256dh = ?, auth = ?, created_at = ?, revoked_at = NULL, revoked_reason = NULL WHERE endpoint = ?",
                ticketId, visitorId, p256dh, auth, ts(now), endpoint);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO push_subscription (id, ticket_id, visitor_id, endpoint, p256dh, auth, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), ticketId, visitorId, endpoint, p256dh, auth, ts(now));
        }
    }

    /** The ticket's own, not-yet-revoked subscriptions: what {@link WebPushChannel#send} sends to. */
    List<Row> activeForTicket(UUID ticketId) {
        return jdbc.query(
                "SELECT id, ticket_id, endpoint, p256dh, auth FROM push_subscription WHERE ticket_id = ? AND revoked_at IS NULL ORDER BY created_at",
                (rs, i) -> new Row(
                        rs.getObject("id", UUID.class), rs.getObject("ticket_id", UUID.class), rs.getString("endpoint"),
                        rs.getString("p256dh"), rs.getString("auth")),
                ticketId);
    }

    /** The push service reported this subscription gone (404/410): stop sending to it (FR-INT-040). */
    void revoke(String endpoint, String reason, Instant now) {
        jdbc.update("UPDATE push_subscription SET revoked_at = ?, revoked_reason = ? WHERE endpoint = ? AND revoked_at IS NULL", ts(now), reason, endpoint);
    }

    private static byte[] decodeBase64Url(String value) {
        String trimmed = value.trim();
        int pad = (4 - trimmed.length() % 4) % 4;
        return Base64.getUrlDecoder().decode(trimmed + "=".repeat(pad));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
