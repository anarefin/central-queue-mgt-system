package com.qms.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.qms.platform.Profiles;
import com.qms.platform.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Issues access tokens (API-011): exactly {@code sub, jti, iss, aud, iat, exp, roles} and, only where a role is
 * scoped, {@code sites} and {@code groups}. No permission list and no other claim is added; permissions are derived
 * from {@code roles} on the server.
 */
@Component
@Profile(Profiles.SERVING)
public class AccessTokenService {

    private final SecurityProperties properties;
    private final SigningKeyStore keys;
    private final Clock clock;

    public AccessTokenService(SecurityProperties properties, SigningKeyStore keys, Clock clock) {
        this.properties = properties;
        this.keys = keys;
        this.clock = clock;
    }

    public AccessToken issue(UUID userId, Set<Role> roles, Set<UUID> siteIds, Set<UUID> groupIds) {
        Instant now = clock.instant();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .jwtID(UUID.randomUUID().toString())
                .issuer(properties.issuer())
                .audience(properties.audience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(properties.accessTokenTtl())))
                .claim("roles", roles.stream().map(Role::wire).sorted().toList());
        if (!siteIds.isEmpty()) {
            claims.claim("sites", siteIds.stream().map(UUID::toString).sorted(Comparator.naturalOrder()).toList());
        }
        if (!groupIds.isEmpty()) {
            claims.claim("groups", groupIds.stream().map(UUID::toString).sorted(Comparator.naturalOrder()).toList());
        }

        SigningKey key = keys.activeKey();
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.kid()).type(JOSEObjectType.JWT).build(), claims.build());
            jwt.sign(new ECDSASigner(key.jwk()));
            return new AccessToken(jwt.serialize(), properties.accessTokenTtl());
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign access token", e);
        }
    }
}
