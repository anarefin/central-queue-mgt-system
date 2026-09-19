package com.qms.platform.security;

import com.qms.platform.ApiException;
import com.qms.platform.ErrorCode;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** The authenticated caller for the current request, taken from the token alone: no session, no database lookup. */
@Component
public class CurrentUser {

    public Optional<AuthenticatedUser> get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
            return Optional.of(AuthenticatedUser.from(jwt.getToken()));
        }
        return Optional.empty();
    }

    public AuthenticatedUser require() {
        return get().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    }
}
