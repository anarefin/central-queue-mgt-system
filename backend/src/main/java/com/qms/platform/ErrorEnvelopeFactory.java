package com.qms.platform;

import com.qms.platform.i18n.Messages;
import com.qms.platform.i18n.RequestLanguage;
import jakarta.servlet.http.HttpServletRequest;
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
        return create(request, exception.code(), exception.messageKey(), exception.messageArgs(), exception.details());
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
