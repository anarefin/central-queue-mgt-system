package com.qms.audit;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** Keyset cursor over {@code (occurred_at, id)}: stable under concurrent inserts and identical timestamps. */
record AuditCursor(OffsetDateTime occurredAt, UUID id) {

    static String encode(OffsetDateTime occurredAt, UUID id) {
        Instant at = occurredAt.toInstant();
        long micros = at.getEpochSecond() * 1_000_000L + at.getNano() / 1000L;
        String raw = micros + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static AuditCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            long micros = Long.parseLong(raw.substring(0, bar));
            UUID id = UUID.fromString(raw.substring(bar + 1));
            Instant at = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L);
            return new AuditCursor(at.atOffset(ZoneOffset.UTC), id);
        } catch (RuntimeException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", "cursor"));
        }
    }
}
