package com.qms.platform;

import com.qms.platform.i18n.Messages;
import com.qms.platform.i18n.RequestLanguage;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Builds §20.3 envelopes with the message resolved for the request and every enabled language in {@code message_i18n}. */
@Component
public class ErrorEnvelopeFactory {

    private final Messages messages;
    private final RequestLanguage requestLanguage;

    public ErrorEnvelopeFactory(Messages messages, RequestLanguage requestLanguage) {
        this.messages = messages;
        this.requestLanguage = requestLanguage;
    }

    public ApiError create(HttpServletRequest request, ErrorCode code) {
        return create(request, code, code.messageKey(), new Object[0], Map.of());
    }

    public ApiError create(HttpServletRequest request, ApiException exception) {
        if (!exception.literalMessages().isEmpty()) return createWithLiterals(request, exception);
        return create(request, exception.code(), exception.messageKey(), exception.messageArgs(), exception.details());
    }

    /** An administrator's own wording, where they gave it for a language, else the pack's text (FR-I18N-011). */
    private ApiError createWithLiterals(HttpServletRequest request, ApiException exception) {
        Map<String, String> all = new LinkedHashMap<>(messages.allLanguages(exception.messageKey(), exception.messageArgs()));
        exception.literalMessages().forEach((language, text) -> {
            if (all.containsKey(language)) all.put(language, text);
        });
        String language = requestLanguage.current(request);
        String message = exception.literalMessages().getOrDefault(language, messages.text(exception.messageKey(), language, exception.messageArgs()));
        return new ApiError(new ApiError.Body(exception.code().wire(), message, all, exception.details(), TraceIdFilter.currentTraceId()));
    }

    public ApiError create(
            HttpServletRequest request, ErrorCode code, String messageKey, Object[] args, Map<String, Object> details) {
        String language = requestLanguage.current(request);
        return new ApiError(new ApiError.Body(
                code.wire(),
                messages.text(messageKey, language, args),
                messages.allLanguages(messageKey, args),
                details,
                TraceIdFilter.currentTraceId()));
    }
}
