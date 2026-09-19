package com.qms.identity;

import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The resource server's decoder (API-010, API-012). It is built from local keys only, pins ES256 rather than reading
 * the algorithm from the token header, and verifies {@code iss}, {@code aud} and {@code exp} on every request. Because
 * it accepts only ES256 with a matching key, {@code alg: none}, HMAC-with-the-public-key and RS256 tokens all fail.
 */
final class JwtDecoderFactory {

    private JwtDecoderFactory() {}

    static JwtDecoder create(SecurityProperties properties, SigningKeyStore store) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(new StoreJwkSource(store))
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                audience(properties.audience()),
                requiredClaims()));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> audience(String expected) {
        OAuth2Error error = new OAuth2Error("invalid_token", "The required audience is missing", null);
        return jwt -> {
            List<String> audience = jwt.getAudience();
            return audience != null && audience.contains(expected)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(error);
        };
    }

    /** The default timestamp validator accepts a token with no {@code exp}; ours must not. */
    private static OAuth2TokenValidator<Jwt> requiredClaims() {
        OAuth2Error error = new OAuth2Error("invalid_token", "A required claim is missing", null);
        return jwt -> jwt.getSubject() != null && jwt.getId() != null && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(error);
    }
}
