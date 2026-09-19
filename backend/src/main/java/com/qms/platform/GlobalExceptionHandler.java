package com.qms.platform;

import com.qms.platform.security.AuthorizationDenials;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Turns every failure into a §20.3 envelope whose code comes from the closed {@link ErrorCode} set. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorEnvelopeFactory envelopes;
    private final AuthorizationDenials denials;

    public GlobalExceptionHandler(ErrorEnvelopeFactory envelopes, AuthorizationDenials denials) {
        this.envelopes = envelopes;
        this.denials = denials;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        var response = ResponseEntity.status(ex.code().status()).contentType(MediaType.APPLICATION_JSON);
        // A 429 says when to try again (API-090).
        if (ex.code() == ErrorCode.RATE_LIMITED && ex.details().get("retry_after_seconds") instanceof Number seconds) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds.longValue()));
        }
        return response.body(envelopes.create(request, ex));
    }

    /** Method security at the service layer throws this from inside a controller call (API-016). */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleDenied(AccessDeniedException ex, HttpServletRequest request) {
        denials.log(request);
        return respond(ErrorCode.FORBIDDEN, envelopes.create(request, ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> handleUnauthenticated(AuthenticationException ex, HttpServletRequest request) {
        return respond(ErrorCode.UNAUTHENTICATED, envelopes.create(request, ErrorCode.UNAUTHENTICATED));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        return respond(ErrorCode.INTERNAL_ERROR, envelopes.create(request, ErrorCode.INTERNAL_ERROR));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::fieldDetail)
                .toList();
        var body = envelopes.create(
                servlet(request),
                ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.messageKey(),
                new Object[0],
                Map.of("fields", fields));
        return typed(ErrorCode.VALIDATION_FAILED, body);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ErrorCode code = ErrorCode.forStatus(status);
        if (code == ErrorCode.INTERNAL_ERROR) {
            log.error("Framework exception", ex);
        }
        return typed(code, envelopes.create(servlet(request), code));
    }

    private static Map<String, String> fieldDetail(FieldError error) {
        return Map.of("field", error.getField(), "code", String.valueOf(error.getCode()));
    }

    private static HttpServletRequest servlet(WebRequest request) {
        return ((ServletWebRequest) request).getRequest();
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code, ApiError body) {
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static ResponseEntity<Object> typed(ErrorCode code, ApiError body) {
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
