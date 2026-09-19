package com.qms.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes §20.3 envelopes from servlet filters, where {@code @RestControllerAdvice} does not apply (401/403). */
@Component
public class ErrorResponseWriter {

    private final ErrorEnvelopeFactory envelopes;
    private final JsonMapper mapper;

    public ErrorResponseWriter(ErrorEnvelopeFactory envelopes, JsonMapper mapper) {
        this.envelopes = envelopes;
        this.mapper = mapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
        write(request, response, code, Map.of());
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, Map<String, Object> details)
            throws IOException {
        ApiError body = envelopes.create(request, code, code.messageKey(), new Object[0], details);
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), body);
    }
}
