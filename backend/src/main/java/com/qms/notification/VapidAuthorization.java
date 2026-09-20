package com.qms.notification;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * The VAPID {@code Authorization} header (ticket 39, RFC 8292): a fresh ES256 JWT per send, signed with the
 * installation's own {@link VapidKeyStore} key, proving to the push service which application server this request
 * comes from. Distinct from — and never involved in — the per-message payload encryption ({@link WebPushEncryption}),
 * which uses its own ephemeral key instead.
 */
final class VapidAuthorization {

    private VapidAuthorization() {}

    /** {@code aud} is the push endpoint's own scheme-plus-host (RFC 8292 §2), never the full endpoint URL. */
    static String header(VapidKeyStore keys, String subject, String endpoint, Duration ttl, Clock clock) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .audience(origin(endpoint))
                .expirationTime(Date.from(now.plus(ttl)))
                .issueTime(Date.from(now))
                .subject(subject)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        try {
            jwt.sign(new ECDSASigner(keys.privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign the VAPID JWT", e);
        }
        return "vapid t=" + jwt.serialize() + ", k=" + keys.publicKeyBase64Url();
    }

    private static String origin(String endpoint) {
        URI uri = URI.create(endpoint);
        String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + port;
    }
}
