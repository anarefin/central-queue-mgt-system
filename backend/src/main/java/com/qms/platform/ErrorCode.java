package com.qms.platform;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * The closed set of API error codes (SRS §20.3). Clients branch on {@link #wire()}, never on the message.
 * Adding a code here requires adding it to docs/api/error-codes.md and a message key {@code error.<code>} in every pack.
 */
public enum ErrorCode {
    UNAUTHENTICATED("unauthenticated", HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS("invalid_credentials", HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID("token_invalid", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("forbidden", HttpStatus.FORBIDDEN),
    ACCOUNT_LOCKED("account_locked", HttpStatus.LOCKED),
    VALIDATION_FAILED("validation_failed", HttpStatus.BAD_REQUEST),
    NOT_FOUND("not_found", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED("method_not_allowed", HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    CONFLICT("conflict", HttpStatus.CONFLICT),
    RATE_LIMITED("rate_limited", HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR("internal_error", HttpStatus.INTERNAL_SERVER_ERROR),
    UNAVAILABLE("unavailable", HttpStatus.SERVICE_UNAVAILABLE);

    private final String wire;
    private final HttpStatus status;

    ErrorCode(String wire, HttpStatus status) {
        this.wire = wire;
        this.status = status;
    }

    public String wire() {
        return wire;
    }

    public HttpStatus status() {
        return status;
    }

    public String messageKey() {
        return "error." + wire;
    }

    /** Maps a framework-generated status to a code from the closed set. */
    public static ErrorCode forStatus(HttpStatusCode status) {
        int value = status.value();
        return switch (value) {
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406, 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 409 -> CONFLICT;
            case 423 -> ACCOUNT_LOCKED;
            case 429 -> RATE_LIMITED;
            case 503 -> UNAVAILABLE;
            default -> status.is4xxClientError() ? VALIDATION_FAILED : INTERNAL_ERROR;
        };
    }
}
