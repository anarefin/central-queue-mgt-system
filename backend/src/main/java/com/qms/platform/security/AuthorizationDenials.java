package com.qms.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Logs every authorisation denial (API-018): a burst of them is the earliest signal of a stolen token. Only the
 * subject, method and path are logged, never headers or the token.
 */
@Component
public class AuthorizationDenials {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationDenials.class);

    private final CurrentUser currentUser;

    public AuthorizationDenials(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    public void log(HttpServletRequest request) {
        String subject = currentUser.get().map(u -> u.userId().toString()).orElse("anonymous");
        log.warn("Authorization denied: subject={} method={} path={}", subject, request.getMethod(), request.getRequestURI());
    }
}
