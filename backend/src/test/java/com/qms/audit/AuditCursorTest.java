package com.qms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditCursorTest {

    @Test
    void roundTripsTimestampAndId() {
        var at = OffsetDateTime.of(2026, 9, 19, 12, 30, 15, 123_456_000, ZoneOffset.ofHours(6));
        var id = UUID.randomUUID();

        AuditCursor decoded = AuditCursor.decode(AuditCursor.encode(at, id));

        assertThat(decoded.occurredAt().toInstant()).isEqualTo(at.toInstant());
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    void isOpaqueUrlSafeText() {
        String cursor = AuditCursor.encode(OffsetDateTime.now(), UUID.randomUUID());
        assertThat(cursor).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void garbageIsAValidationErrorNotAServerError() {
        for (String bad : new String[] {"not-a-cursor", "", "%%%", Base64.getUrlEncoder().encodeToString("x|y".getBytes())}) {
            assertThatThrownBy(() -> AuditCursor.decode(bad))
                    .as(bad)
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }
}
