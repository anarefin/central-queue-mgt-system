package com.qms.platform.idempotency;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replaying an {@code Idempotency-Key} within 24 hours returns the original result instead of acting twice
 * (SRS §20.1). The claim on the key is made in the same transaction as the action, so a request that fails leaves no
 * claim behind and a retry runs again, while two requests with the same key that arrive together run the action once:
 * the second waits on the first's row and then replays its stored response.
 *
 * <p>A key belongs to one caller and one endpoint ({@code scope}); reusing it for a different request is a
 * {@code conflict}, never a silent replay of an unrelated result. The stored response is a short-lived replay cache
 * (rows are purged once older than the window), not a second copy of the data.
 */
@Component
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final Duration WINDOW = Duration.ofHours(24);
    private static final int MAX_KEY_LENGTH = 200;

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final Clock clock;

    IdempotencyService(JdbcTemplate jdbc, JsonMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** The outcome of {@link #execute}: the value and whether it is the stored result of an earlier request. */
    public record Result<T>(T value, boolean replayed) {}

    /**
     * Runs {@code action} once for the key. {@code fingerprint} summarises the request, so the same key with a
     * different request is refused. A missing or unusable key is a {@code validation_failed}.
     */
    @Transactional
    public <T> Result<T> execute(String scope, String key, String fingerprint, Class<T> type, Supplier<T> action) {
        requireUsable(key);
        Instant now = clock.instant();
        purgeExpired(now);

        for (int attempt = 0; attempt < 2; attempt++) {
            int claimed = jdbc.update(
                    "INSERT INTO idempotency_key (scope, idem_key, fingerprint, response_body, created_at)"
                            + " VALUES (?, ?, ?, '', ?) ON CONFLICT (scope, idem_key) DO NOTHING",
                    scope, key, fingerprint, timestamp(now));
            if (claimed == 1) {
                T value = action.get();
                jdbc.update(
                        "UPDATE idempotency_key SET response_body = ? WHERE scope = ? AND idem_key = ?",
                        mapper.writeValueAsString(value), scope, key);
                return new Result<>(value, false);
            }
            List<Stored> existing = jdbc.query(
                    "SELECT fingerprint, response_body, created_at FROM idempotency_key WHERE scope = ? AND idem_key = ?",
                    (rs, i) -> new Stored(rs.getString("fingerprint"), rs.getString("response_body"), rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                    scope, key);
            if (existing.isEmpty()) continue; // the other request rolled back between our insert and our read
            Stored stored = existing.getFirst();
            if (stored.createdAt().isBefore(now.minus(WINDOW))) {
                jdbc.update("DELETE FROM idempotency_key WHERE scope = ? AND idem_key = ?", scope, key);
                continue;
            }
            if (!stored.fingerprint().equals(fingerprint)) {
                throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "idempotency_key_reused"));
            }
            return new Result<>(mapper.readValue(stored.body(), type), true);
        }
        throw new ApiException(ErrorCode.CONFLICT, Map.of("reason", "idempotency_key_in_use"));
    }

    private record Stored(String fingerprint, String body, Instant createdAt) {}

    /** A missing, blank or oversized key is a {@code validation_failed} naming the header. */
    public static void requireUsable(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(Map.of("field", HEADER, "code", "required"))));
        }
    }

    private void purgeExpired(Instant now) {
        jdbc.update(
                "DELETE FROM idempotency_key WHERE (scope, idem_key) IN"
                        + " (SELECT scope, idem_key FROM idempotency_key WHERE created_at < ? FOR UPDATE SKIP LOCKED)",
                timestamp(now.minus(WINDOW)));
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
