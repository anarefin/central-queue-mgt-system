package com.qms.identity;

import com.qms.platform.ErrorCode;
import com.qms.platform.ErrorResponseWriter;
import com.qms.platform.Profiles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** 401 in the §20.3 envelope: {@code token_invalid} when a bearer token was presented and failed, else {@code unauthenticated}. */
@Component
@Profile(Profiles.SERVING)
class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ErrorResponseWriter writer;

    ApiAuthenticationEntryPoint(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        ErrorCode code = exception instanceof OAuth2AuthenticationException ? ErrorCode.TOKEN_INVALID : ErrorCode.UNAUTHENTICATED;
        writer.write(request, response, code);
    }
}
