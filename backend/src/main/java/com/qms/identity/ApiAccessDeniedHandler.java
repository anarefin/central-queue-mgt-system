package com.qms.identity;

import com.qms.platform.ErrorCode;
import com.qms.platform.ErrorResponseWriter;
import com.qms.platform.Profiles;
import com.qms.platform.security.AuthorizationDenials;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** 403 in the §20.3 envelope, and the denial is logged (API-018). */
@Component
@Profile(Profiles.SERVING)
class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ErrorResponseWriter writer;
    private final AuthorizationDenials denials;

    ApiAccessDeniedHandler(ErrorResponseWriter writer, AuthorizationDenials denials) {
        this.writer = writer;
        this.denials = denials;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) throws IOException {
        denials.log(request);
        writer.write(request, response, ErrorCode.FORBIDDEN);
    }
}
