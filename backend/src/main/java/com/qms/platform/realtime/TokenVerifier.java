package com.qms.platform.realtime;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * Turns a bearer token into the authentication REST would build for it, so a {@code reauth} frame is judged by exactly the
 * rules the handshake was (signature, issuer, audience, expiry, claims). Throws when the token is not acceptable.
 */
public interface TokenVerifier {

    Authentication verify(String token) throws AuthenticationException;
}
