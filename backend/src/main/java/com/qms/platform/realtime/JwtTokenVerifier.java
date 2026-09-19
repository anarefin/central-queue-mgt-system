package com.qms.platform.realtime;

import com.qms.platform.Profiles;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;

/** The resource server's own decoder and converter, applied to the token in a {@code reauth} frame (ADR-0009). */
@Component
@Profile(Profiles.SERVING)
class JwtTokenVerifier implements TokenVerifier {

    private final JwtDecoder decoder;
    private final JwtAuthenticationConverter converter;

    JwtTokenVerifier(JwtDecoder decoder, JwtAuthenticationConverter converter) {
        this.decoder = decoder;
        this.converter = converter;
    }

    @Override
    public Authentication verify(String token) throws AuthenticationException {
        try {
            return converter.convert(decoder.decode(token));
        } catch (JwtException | IllegalArgumentException rejected) {
            throw new BadCredentialsException("the token was not accepted", rejected);
        }
    }
}
